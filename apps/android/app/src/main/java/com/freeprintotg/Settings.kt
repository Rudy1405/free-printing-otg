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
