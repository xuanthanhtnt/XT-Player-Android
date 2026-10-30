package com.xuanthanhtnt.xtplayer.network

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.xuanthanhtnt.xtplayer.core.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Thiết bị này": internal storage, SD cards and USB drives, browsed in the app like a NAS share (with the same
 * back navigation, thumbnails and "add to library"). Files are read directly by path.
 */
object LocalStorage {
    const val SERVER_ID = "local"

    val credentials = ServerCredentials(
        id = SERVER_ID, kind = ServerKind.LOCAL, host = "Thiết bị này", port = 0, username = "", password = "",
        domain = "", useTLS = false, basePath = "/", allowInsecureTLS = false,
    )

    data class Volume(val name: String, val path: String)

    /** Storage roots: the primary storage first, then removable SD cards / USB drives. */
    fun volumes(context: Context): List<Volume> {
        val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val roots = mutableListOf<Volume>()
        val primary = Environment.getExternalStorageDirectory()
        roots += Volume("Bộ nhớ trong", primary.absolutePath)
        // Each volume's app folder is <root>/Android/data/<package>/files.
        for (dir in context.getExternalFilesDirs(null).filterNotNull()) {
            val root = File(dir.absolutePath.substringBefore("/Android/"))
            if (root.absolutePath == primary.absolutePath || roots.any { it.path == root.absolutePath }) continue
            val name = runCatching { storage.getStorageVolume(root)?.getDescription(context) }.getOrNull() ?: root.name
            roots += Volume(name, root.absolutePath)
        }
        // USB drives some TV boxes mount without an app folder.
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name !in setOf("emulated", "self") }?.forEach { dir ->
            if (roots.none { it.path == dir.absolutePath }) {
                val name = runCatching { storage.getStorageVolume(dir)?.getDescription(context) }.getOrNull() ?: dir.name
                roots += Volume(name, dir.absolutePath)
            }
        }
        return roots
    }

    /** Whether every file (videos and their .srt / .ass subtitles) can be read. */
    fun hasFullAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else hasReadPermission(context)

    fun hasReadPermission(context: Context): Boolean =
        runtimePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Runtime permission(s) that at least show videos (no subtitles on Android 13+ without full access). */
    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Android 11+: the system page "Quyền truy cập mọi tệp". Returns false when the device has no such page (some TV boxes). */
    fun openFullAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}

class LocalFileSystem : RemoteFileSystem {
    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        if (path.isEmpty()) {
            return@withContext LocalStorage.volumes(com.xuanthanhtnt.xtplayer.AppGraph.context)
                .map { RemoteItem(it.name, it.path, isDirectory = true, size = 0, modified = null) }
        }
        val files = File(path).listFiles()
            ?: throw RemoteException("Không đọc được thư mục — cho phép XT-Player truy cập tệp (Cài đặt ▸ Ứng dụng ▸ XT-Player ▸ Quyền).")
        files
            .filter { !it.name.startsWith(".") && !it.isHidden }
            .map { RemoteItem(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }
}
