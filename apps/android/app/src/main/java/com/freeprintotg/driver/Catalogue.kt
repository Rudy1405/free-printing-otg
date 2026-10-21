package com.freeprintotg.driver

import android.os.Build
import com.freeprintotg.DeviceId
import org.json.JSONObject

data class PackFile(val file: String, val sha256: String, val size: Long)

data class EnginePack(val id: String, val version: String, val env: Map<String, String>, val abis: Map<String, PackFile>) {
    /** First ABI this device runs that the pack was built for. */
    fun forDevice(supported: Array<String> = Build.SUPPORTED_ABIS): Pair<String, PackFile>? =
        supported.firstNotNullOfOrNull { abi -> abis[abi]?.let { abi to it } }
}

data class CatalogueEntry(
    val engine: String,
    val file: String,
    val sha256: String,
    val manufacturer: String?,
    val model: String?,
    val mfg: String?,
    val mdl: String?,
    val commands: List<String>,
    val generic: Boolean,
    val experimental: String?,
)

/** `catalogue.json` produced by tools/pack/build_dist.py and hosted next to the packs and PPDs. */
class Catalogue(val baseUrl: String, val engines: Map<String, EnginePack>, val printers: List<CatalogueEntry>) {

    /** How sure we are about a match; higher is better. */
    enum class Quality { GENERIC, MODEL_NAME, DEVICE_ID }
    data class Match(val entry: CatalogueEntry, val quality: Quality)

    // Engine tried first per manufacturer: the vendor's own driver beats Gutenprint's reverse-engineered one.
    private val vendorEngine = mapOf(
        "epson" to "escpr", "hp" to "hplip", "hewlett-packard" to "hplip",
        "samsung" to "splix", "xerox" to "splix", "dell" to "splix",
    )

    private val byKey: Map<String, List<CatalogueEntry>> by lazy {
        buildMap<String, MutableList<CatalogueEntry>> {
            for (p in printers) for (k in keysOf(p)) getOrPut(k) { mutableListOf() }.add(p)
        }
    }

    private fun keysOf(p: CatalogueEntry) =
        listOfNotNull(p.model, p.mdl, listOfNotNull(p.mfg, p.mdl).joinToString(" ").ifBlank { null })
            .map(::norm).filter { it.length >= 3 }.toSet()

    fun match(id: DeviceId?, displayName: String): Match? {
        val mdl = id?.mdl?.takeIf { it.isNotBlank() }
        val candidates = buildList {
            if (mdl != null) {
                add(norm("${id.mfg.orEmpty()} $mdl") to Quality.DEVICE_ID)
                add(norm(mdl) to Quality.DEVICE_ID)
            }
            add(norm(displayName) to Quality.MODEL_NAME)
        }
        for ((key, q) in candidates) {
            val hits = byKey[key] ?: continue
            return Match(rank(hits, id?.mfg), q)
        }
        return generic(id)
    }

    private fun rank(hits: List<CatalogueEntry>, mfg: String?): CatalogueEntry {
        val pref = vendorEngine[mfg?.lowercase()]
        return hits.firstOrNull { it.engine == pref } ?: hits.minBy { enginePriority(it.engine) }
    }

    private fun enginePriority(e: String) = when (e) { "escpr" -> 0; "hplip" -> 1; "splix" -> 2; "gutenprint" -> 3; else -> 4 }

    /** No model match: pick a Generic PPD from the printer's IEEE-1284 command sets. */
    private fun generic(id: DeviceId?): Match? {
        val cmds = id?.commands.orEmpty().map { it.uppercase() }
        val g = printers.filter { it.generic }
        fun pick(pred: (CatalogueEntry) -> Boolean) = g.firstOrNull(pred)?.let { Match(it, Quality.GENERIC) }
        return when {
            cmds.any { it.startsWith("ESCPR") || it == "ESC/P-R" } -> pick { it.engine == "escpr" }
            cmds.any { it.startsWith("PCL") } -> {
                val color = cmds.any { it == "PCLXL" || it.contains("PCL5C") }
                pick { it.engine == "hplip" && it.commands.contains(if (color) "PCL5C" else "PCL5E") }
            }
            cmds.any { it == "POSTSCRIPT" || it == "PS" || it.startsWith("POSTSCRIPT") } -> pick { it.engine == "builtin-ps" }
            else -> null
        }
    }

    companion object {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        fun parse(json: String): Catalogue {
            val o = JSONObject(json)
            val engines = o.getJSONObject("engines").let { e ->
                e.keys().asSequence().associateWith { id ->
                    val x = e.getJSONObject(id)
                    EnginePack(
                        id, x.getString("version"),
                        x.getJSONObject("env").let { en -> en.keys().asSequence().associateWith(en::getString) },
                        x.getJSONObject("abis").let { a ->
                            a.keys().asSequence().associateWith { abi ->
                                val f = a.getJSONObject(abi)
                                PackFile(f.getString("file"), f.getString("sha256"), f.getLong("size"))
                            }
                        },
                    )
                }
            }
            val arr = o.getJSONArray("printers")
            val printers = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                fun s(k: String) = if (p.isNull(k)) null else p.optString(k).takeIf { it.isNotEmpty() }
                CatalogueEntry(
                    p.getString("engine"), p.getString("file"), p.getString("sha256"),
                    s("manufacturer"), s("model"), s("mfg"), s("mdl"),
                    p.optJSONArray("cmd")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                    p.optBoolean("generic"), s("experimental"),
                )
            }
            return Catalogue(o.optString("base_url"), engines, printers)
        }
    }
}
