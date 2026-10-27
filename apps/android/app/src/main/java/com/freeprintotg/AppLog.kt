package com.freeprintotg

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app log for hardware testing: shown on screen and copyable, so a failure can be reported without adb. */
object AppLog {
    val lines = mutableStateListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun d(msg: String) {
        android.util.Log.d("FreePrintOTG", msg)
        val line = "${fmt.format(Date())} $msg"
        synchronized(lines) {
            lines += line
            if (lines.size > 300) lines.removeRange(0, lines.size - 300)
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
