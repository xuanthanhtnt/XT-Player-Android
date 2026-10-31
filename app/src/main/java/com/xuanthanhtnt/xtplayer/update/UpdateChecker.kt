package com.xuanthanhtnt.xtplayer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.BuildConfig
import com.xuanthanhtnt.xtplayer.core.Http
import com.xuanthanhtnt.xtplayer.metadata.LenientJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File

/**
 * Looks for a newer XT-Player on GitHub Releases (CI publishes one per `v*` tag with the APK), downloads the
 * APK and hands it to the system installer — the Android version of the Mac app's DMG updater.
 */
class UpdateChecker {
    data class Release(
        val tag: String,
        val version: String,
        val notes: String,
        val page: String,
        val download: String?,
        val downloadSize: Long,
    )

    sealed class Phase {
        data object Idle : Phase()
        data object Checking : Phase()
        data object UpToDate : Phase()
        data object Available : Phase()
        data class Downloading(val fraction: Float) : Phase()
        data class Failed(val message: String) : Phase()
    }

    private val phaseState = MutableStateFlow<Phase>(Phase.Idle)
    private val latestState = MutableStateFlow<Release?>(null)
    private val promptState = MutableStateFlow<Release?>(null)
    val phase: StateFlow<Phase> = phaseState
    val latest: StateFlow<Release?> = latestState
    /** A newer version the user has not skipped: the app asks whether to update. */
    val prompt: StateFlow<Release?> = promptState

    private val prefs get() = AppGraph.settings.prefs

    companion object {
        val currentVersion: String get() = BuildConfig.VERSION_NAME.removeSuffix("-debug")

        /** "0.10.1" > "0.9.3"; missing parts count as 0. */
        fun isNewer(candidate: String, than: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = than.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** App releases only: "v0.2.8". Other tags on the repository (prebuilt FFmpeg for the build) are not updates. */
        fun isAppRelease(tag: String): Boolean = tag.startsWith("v")

        private const val LAST_CHECK_KEY = "update.lastCheck"
        private const val SKIPPED_KEY = "update.skippedVersion"
    }

    /** At launch, at most about once a day. */
    suspend fun checkIfDue() {
        val last = prefs.getLong(LAST_CHECK_KEY, 0)
        if (System.currentTimeMillis() - last < 20 * 3600 * 1000L) return
        check(userInitiated = false)
    }

    suspend fun check(userInitiated: Boolean) {
        if (phaseState.value == Phase.Checking || phaseState.value is Phase.Downloading) return
        phaseState.value = Phase.Checking
        try {
            val release = fetchNewest()
            prefs.edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
            latestState.value = release
            if (release != null && isNewer(release.version, currentVersion)) {
                phaseState.value = Phase.Available
                if (userInitiated || prefs.getString(SKIPPED_KEY, null) != release.version) promptState.value = release
            } else {
                phaseState.value = Phase.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phaseState.value = if (userInitiated) Phase.Failed("Không kiểm tra được bản cập nhật: ${e.message}") else Phase.Idle
        }
    }

    fun dismissPrompt() {
        promptState.value = null
    }

    fun skip(release: Release) {
        prefs.edit().putString(SKIPPED_KEY, release.version).apply()
        promptState.value = null
    }

    /** Whether Android lets XT-Player install APKs (Settings ▸ Install unknown apps). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Downloads the APK into cache/updates and opens the system installer. */
    suspend fun install(context: Context, release: Release) {
        val url = release.download
        if (url == null) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        promptState.value = null
        try {
            val file = download(context, url, release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            phaseState.value = Phase.Available
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            phaseState.value = Phase.Failed("Không tải được bản cập nhật: ${e.message}")
        }
    }

    private suspend fun download(context: Context, url: String, release: Release): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val target = File(folder, "XT-Player-${release.version}.apk")
        phaseState.value = Phase.Downloading(0f)
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("không có dữ liệu")
            val total = body.contentLength().takeIf { it > 0 } ?: release.downloadSize
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) phaseState.value = Phase.Downloading((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        target
    }

    // GitHub

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val assets: List<Asset> = emptyList(),
    ) {
        @Serializable
        data class Asset(
            val name: String,
            @SerialName("browser_download_url") val url: String,
            val size: Long = 0,
        )
    }

    private suspend fun fetchNewest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases?per_page=15")
            .header("Accept", "application/vnd.github+json")
            .build()
        val text = Http.client.newCall(request).execute().use { response ->
            if (response.code != 200) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        LenientJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubRelease.serializer()), text)
            .filter { !it.draft && isAppRelease(it.tagName) }
            .map { raw ->
                val asset = raw.assets.firstOrNull { it.name.lowercase().endsWith(".apk") }
                Release(
                    tag = raw.tagName,
                    version = raw.tagName.removePrefix("v"),
                    notes = raw.body.orEmpty(),
                    page = raw.htmlUrl,
                    download = asset?.url,
                    downloadSize = asset?.size ?: 0,
                )
            }
            .reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best }
    }
}
