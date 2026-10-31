package com.xuanthanhtnt.xtplayer.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diagnostics log (MPVLogBuffer of the Apple app): the last lines in memory for the Settings page, also sent
 * to logcat. Trakt writes "[trakt] …" lines here.
 */
object AppLog {
    private const val LIMIT = 300
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$time $line")
        while (lines.size > LIMIT) lines.removeFirst()
        runCatching { android.util.Log.i("XTPlayer", line) }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
