package com.xuanthanhtnt.xtplayer.metadata

import android.content.Context
import com.xuanthanhtnt.xtplayer.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest

/**
 * Video frames used as artwork (items TMDb does not know, Continue Watching, the NAS browser), stored as
 * JPEGs and referenced as "local:<sha1>.jpg" in the same fields that hold TMDb image paths.
 */
object ThumbnailStore {
    private const val PREFIX = "local:"
    /** A file without a picture is tried again after a week (LibraryScanner.swift). */
    private const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    private val directory: File by lazy { File(AppGraph.context.filesDir, "Thumbnails").apply { mkdirs() } }
    private val failures by lazy { AppGraph.context.getSharedPreferences("thumbnail_failures", Context.MODE_PRIVATE) }

    private val writes = MutableStateFlow(0L)

    /** Bumped whenever a picture is written: artwork waiting for a missing file shows it then (ThumbnailStore.announce). */
    val written: StateFlow<Long> = writes

    fun reference(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$PREFIX$digest.jpg"
    }

    fun isLocal(path: String?): Boolean = path?.startsWith(PREFIX) == true

    fun file(reference: String?): File? {
        if (reference == null || !reference.startsWith(PREFIX)) return null
        val name = reference.removePrefix(PREFIX)
        if (name.contains('/') || name.contains("..")) return null
        return File(directory, name)
    }

    /** A TMDb path, or a local frame that still exists (the system may clear app storage). */
    fun isUsable(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        if (!isLocal(path)) return true
        return file(path)?.exists() == true
    }

    /** A picture was just written. */
    fun announce() {
        writes.value = writes.value + 1
    }

    /** The file had no picture to show less than a week ago (a track without a cover, a broken file). */
    fun recentlyFailed(key: String, now: Long = System.currentTimeMillis()): Boolean =
        isRecent(failures.getLong(key, 0L), now)

    fun markFailed(key: String, now: Long = System.currentTimeMillis()) {
        failures.edit().putLong(key, now).apply()
    }

    internal fun isRecent(failedAt: Long, now: Long): Boolean = failedAt > 0 && now - failedAt < RETRY_AFTER_MS
}
