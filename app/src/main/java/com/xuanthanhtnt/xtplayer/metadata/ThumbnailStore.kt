package com.xuanthanhtnt.xtplayer.metadata

import com.xuanthanhtnt.xtplayer.AppGraph
import java.io.File
import java.security.MessageDigest

/**
 * Video frames used as artwork (items TMDb does not know, Continue Watching, the NAS browser), stored as
 * JPEGs and referenced as "local:<sha1>.jpg" in the same fields that hold TMDb image paths.
 */
object ThumbnailStore {
    private const val PREFIX = "local:"

    private val directory: File by lazy { File(AppGraph.context.filesDir, "Thumbnails").apply { mkdirs() } }

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
}
