package com.freeprintotg.driver

import android.content.Context
import com.freeprintotg.*
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Output language a driver produces. Each maps to one encoder in [com.freeprintotg.render.Encoders]. */
enum class Language { PDF, PWG_RASTER, POSTSCRIPT, PCL_MONO }

data class DriverEntry(
    val id: String,
    val name: String,
    val language: Language,
    val mfg: List<String>,
    val mdlRegex: Regex?,
    val commands: List<String>,
    val url: String?,
    val sha256: String?,
) {
    val builtin get() = url == null
}

/** [fallback] marks a last-resort guess; the caller may replace it with an engine pack from the hosted catalogue. */
data class Plan(val language: Language, val driver: DriverEntry?, val note: String, val fallback: Boolean = false)

/**
 * Chooses how to talk to a printer. Order mirrors what NokoPrint does with its driver tables
 * (core/b.java: MFG+MDL rows grouped per driver library) with driverless formats tried first:
 *  1. Format the printer advertises: PDF, then PWG raster (IPP Everywhere / AirPrint class).
 *  2. Manifest entry matching IEEE-1284 MFG/MDL: a builtin language or a downloadable pack.
 *  3. IEEE-1284 CMD list (PDF, POSTSCRIPT, PWGRaster, PCL).
 *  4. Generic PCL fallback.
 */
class DriverResolver(private val entries: List<DriverEntry>) {

    fun resolve(printer: Printer, advertised: Set<String>): Plan {
        val fmts = (advertised + printer.formats).map { it.lowercase() }.toSet()
        if ("application/pdf" in fmts) return Plan(Language.PDF, null, "Driverless PDF")
        if ("image/pwg-raster" in fmts) return Plan(Language.PWG_RASTER, null, "Driverless PWG raster")

        val id = printer.deviceId
        entries.firstOrNull { matches(it, id) }?.let { return Plan(it.language, it, "Driver: ${it.name}") }

        val cmds = id?.commands.orEmpty().map { it.uppercase() }
        return when {
            cmds.any { it == "PDF" } -> Plan(Language.PDF, null, "Printer reports PDF")
            cmds.any { it.startsWith("PWG") } -> Plan(Language.PWG_RASTER, null, "Printer reports PWG raster")
            cmds.any { it == "POSTSCRIPT" || it == "PS" || it.startsWith("POSTSCRIPT") } -> Plan(Language.POSTSCRIPT, null, "Printer reports PostScript")
            cmds.any { it.startsWith("PCL") } -> Plan(Language.PCL_MONO, null, "Generic PCL driver", fallback = true)
            else -> Plan(Language.PCL_MONO, null, "Unknown printer: generic PCL fallback (may not work)", fallback = true)
        }
    }

    private fun matches(e: DriverEntry, id: DeviceId?): Boolean {
        if (id == null) return false
        if (e.mfg.isNotEmpty() && e.mfg.none { it.equals(id.mfg, ignoreCase = true) }) return false
        if (e.mdlRegex != null && id.mdl?.let(e.mdlRegex::containsMatchIn) != true) return false
        if (e.commands.isNotEmpty() && e.commands.none { c -> id.commands.any { it.equals(c, true) } }) return false
        return e.mfg.isNotEmpty() || e.mdlRegex != null
    }

    companion object {
        fun parse(json: String): List<DriverEntry> {
            val arr = JSONObject(json).getJSONArray("drivers")
            return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val m = o.optJSONObject("match")
                fun list(k: String) = m?.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
                DriverEntry(
                    id = o.getString("id"), name = o.getString("name"),
                    language = Language.valueOf(o.getString("language")),
                    mfg = list("mfg"), mdlRegex = m?.optString("mdl")?.takeIf { it.isNotEmpty() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
                    commands = list("cmd"), url = o.optString("url").ifEmpty { null }, sha256 = o.optString("sha256").ifEmpty { null },
                )
            }
        }
    }
}

/**
 * Downloads driver packs listed in the manifest. A pack is a zip with a `driver.json`; it is only
 * kept when its SHA-256 equals the manifest value, so a hijacked mirror cannot slip code or data in.
 */
class DriverStore(private val ctx: Context) {
    private val root get() = File(ctx.filesDir, "drivers").apply { mkdirs() }

    fun bundled(): List<DriverEntry> =
        DriverResolver.parse(ctx.assets.open("drivers.json").bufferedReader().use { it.readText() })

    fun fetchManifest(url: String): List<DriverEntry> =
        DriverResolver.parse(URL(url).openStream().bufferedReader().use { it.readText() })

    fun isInstalled(e: DriverEntry) = e.builtin || File(root, "${e.id}/driver.json").exists()

    /** Returns the installed directory. Throws when the hash does not match. */
    fun install(e: DriverEntry): File {
        val url = e.url ?: return root
        val expected = e.sha256 ?: error("Manifest entry ${e.id} has no sha256; refusing to install")
        val bytes = URL(url).openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual.equals(expected, ignoreCase = true)) { "Driver pack hash mismatch for ${e.id}" }
        val dir = File(root, e.id).apply { deleteRecursively(); mkdirs() }
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { z ->
                val f = File(dir, z.name).canonicalFile
                require(f.path.startsWith(dir.canonicalPath + File.separator)) { "Bad zip entry ${z.name}" }
                if (z.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        return dir
    }
}
