package com.freeprintotg

import android.content.Context

/** User-editable settings. The driver server URL overrides the build-time default so a test device can point at a PC. */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun catalogueUrl(ctx: Context): String =
        prefs(ctx).getString("catalogue_url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CATALOGUE_URL

    fun setCatalogueUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString("catalogue_url", url.trim().trimEnd('/')).apply()
    }
}

/** Languages the setup assistant confirmed, keyed by [com.freeprintotg.driver.PrinterIdentity.key]. */
object LearnedLanguages {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("learned_languages", Context.MODE_PRIVATE)

    fun all(ctx: Context): Map<String, com.freeprintotg.driver.Language> =
        prefs(ctx).all.mapNotNull { (k, v) ->
            runCatching { com.freeprintotg.driver.Language.valueOf(v as String) }.getOrNull()?.let { k to it }
        }.toMap()

    fun save(ctx: Context, key: String, lang: com.freeprintotg.driver.Language) {
        prefs(ctx).edit().putString(key, lang.name).apply()
    }

    fun forget(ctx: Context, key: String) {
        prefs(ctx).edit().remove(key).apply()
    }
}
