package com.xuanthanhtnt.xtplayer.core

import android.content.Context
import android.os.Build
import com.xuanthanhtnt.xtplayer.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash so the next launch can show it ("Sao chép" / "Chia sẻ") — the Android
 * counterpart of the player log the Apple app offers for bug reports.
 */
object CrashLog {
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(): String? = if (::file.isInitialized && file.exists()) runCatching { file.readText() }.getOrNull() else null

    fun clear() {
        if (::file.isInitialized) file.delete()
    }

    /** A handled error worth reporting (e.g. the player could not start). */
    fun record(error: Throwable) {
        if (::file.isInitialized) runCatching { file.writeText(report(Thread.currentThread(), error)) }
    }

    private fun report(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("XT-Player Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — $time")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
    }
}
