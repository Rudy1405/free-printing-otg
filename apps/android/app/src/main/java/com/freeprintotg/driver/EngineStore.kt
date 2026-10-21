package com.freeprintotg.driver

import android.content.Context
import android.os.ParcelFileDescriptor
import com.freeprintotg.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class EngineException(msg: String) : Exception(msg)

/**
 * Downloads the catalogue, engine packs and per-model PPDs from the hosted site, checks SHA-256 against
 * the catalogue, and runs an engine as a CUPS filter. Unpacked files are made read-only because Android 14
 * refuses to load writable code.
 */
class EngineStore(private val ctx: Context, private val baseUrl: String = BuildConfig.CATALOGUE_URL.trimEnd('/')) {
    private val root get() = File(ctx.filesDir, "engines").apply { mkdirs() }
    private val cacheFile get() = File(root, "catalogue.json")

    fun configured() = baseUrl.isNotBlank()

    /** Cached catalogue if present, else downloads it. */
    fun catalogue(refresh: Boolean = false): Catalogue {
        if (!refresh && cacheFile.exists()) return Catalogue.parse(cacheFile.readText())
        if (!configured()) throw EngineException("No driver catalogue URL configured (build with -PcatalogueUrl=...)")
        val text = download("$baseUrl/catalogue.json").toString(Charsets.UTF_8)
        val cat = Catalogue.parse(text) // validate before caching
        cacheFile.writeText(text)
        return cat
    }

    private fun packDir(p: EnginePack, abi: String) = File(root, "packs/${p.id}-${p.version}-$abi")

    fun isInstalled(p: EnginePack) = p.forDevice()?.let { (abi, _) -> File(packDir(p, abi), "driver.json").exists() } ?: false

    /** Installs the engine pack for this device's ABI if missing; returns its directory. */
    fun ensurePack(p: EnginePack): File {
        val (abi, pf) = p.forDevice()
            ?: throw EngineException("No ${p.id} build for this device (${android.os.Build.SUPPORTED_ABIS.joinToString()})")
        val dir = packDir(p, abi)
        if (File(dir, "driver.json").exists()) return dir
        val bytes = verified("$baseUrl/${pf.file}", pf.sha256)
        val tmp = File(dir.parentFile, dir.name + ".tmp").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { z ->
                val f = File(tmp, z.name).canonicalFile
                if (!f.path.startsWith(tmp.canonicalPath + File.separator)) throw EngineException("Bad zip entry ${z.name}")
                if (z.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        tmp.walkTopDown().filter { it.isFile }.forEach { it.setReadOnly() }
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw EngineException("Cannot install ${p.id}")
        return dir
    }

    /** Decompressed PPD for a catalogue entry, cached by hash. */
    fun ensurePpd(e: CatalogueEntry): File {
        val f = File(root, "ppd/${e.sha256}.ppd")
        if (f.exists()) return f
        val gz = verified("$baseUrl/${e.file}", e.sha256)
        f.parentFile?.mkdirs()
        GZIPInputStream(gz.inputStream()).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /**
     * Runs [pack] on [raster] (PWG/CUPS raster) and returns the printer-ready bytes.
     * Same call shape as a CUPS filter: argv = name job user title copies options, PPD in the environment.
     */
    fun run(pack: EnginePack, ppd: File, raster: ByteArray, title: String, copies: Int, options: String): ByteArray {
        val dir = ensurePack(pack)
        val manifest = JSONObject(File(dir, "driver.json").readText())
        val lib = File(dir, manifest.getString("library"))
        val env = buildList {
            add("PPD=${ppd.path}")
            add("TMPDIR=${ctx.cacheDir.path}")
            add("HOME=${ctx.filesDir.path}")
            pack.env.forEach { (k, v) -> add("$k=${v.replace("\${PACK}", dir.path)}") }
        }
        val argv = arrayOf(lib.name, "1", "app", title.ifBlank { "document" }, copies.toString(), options)

        val inP = ParcelFileDescriptor.createPipe()   // [0] read side (child stdin), [1] write side (us)
        val outP = ParcelFileDescriptor.createPipe()  // [0] read side (us), [1] write side (child stdout)
        val errP = ParcelFileDescriptor.createPipe()
        val pid = NativeRunner.spawn(lib.path, argv, env.toTypedArray(), inP[0].fd, outP[1].fd, errP[1].fd)
        // the child has its own copies; close ours so EOF reaches both sides
        inP[0].close(); outP[1].close(); errP[1].close()
        if (pid < 0) {
            listOf(inP[1], outP[0], errP[0]).forEach { it.close() }
            throw EngineException(when (pid) {
                -1000 -> "Cannot load ${lib.name} (dlopen failed)"
                -1001 -> "${lib.name} has no fpo_entry"
                else -> "fork failed ($pid)"
            })
        }
        val err = ByteArrayOutputStream()
        val out = ByteArrayOutputStream()
        val tErr = thread(name = "engine-stderr") { ParcelFileDescriptor.AutoCloseInputStream(errP[0]).use { it.copyTo(err) } }
        val tOut = thread(name = "engine-stdout") { ParcelFileDescriptor.AutoCloseInputStream(outP[0]).use { it.copyTo(out) } }
        runCatching { ParcelFileDescriptor.AutoCloseOutputStream(inP[1]).use { it.write(raster) } }
        val rc = NativeRunner.waitFor(pid)
        tOut.join(); tErr.join()
        if (rc != 0 || out.size() == 0) {
            val tail = err.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(4).joinToString(" | ")
            throw EngineException("${pack.id} exited with $rc${if (tail.isNotEmpty()) ": $tail" else ""}")
        }
        return out.toByteArray()
    }

    private fun verified(url: String, sha256: String): ByteArray {
        val bytes = download(url)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256, ignoreCase = true)) throw EngineException("Hash mismatch for $url")
        return bytes
    }

    private fun download(url: String): ByteArray = URL(url).openStream().use { it.readBytes() }
}
