package com.xuanthanhtnt.xtplayer.metadata

import android.net.Uri
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.BuildConfig
import com.xuanthanhtnt.xtplayer.core.AppSettings
import com.xuanthanhtnt.xtplayer.core.Http
import com.xuanthanhtnt.xtplayer.player.SmbFileReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles.com REST API v1: search (file hash + title), download, and an optional login that raises the
 * daily download quota. Needs a free API key (Settings ▸ OpenSubtitles, or the CI secret OPENSUBTITLES_API_KEY).
 */
class OpenSubtitlesClient(private val apiKey: String, private val username: String?, private val password: String?) {
    data class Subtitle(
        val id: String,
        val fileID: Int,
        val language: String,
        val release: String,
        val downloads: Int,
        val hearingImpaired: Boolean,
        val machineTranslated: Boolean,
        /** Found through the file hash: made for exactly this release. */
        val hashMatch: Boolean,
    )

    class Failure(val code: Int, detail: String) : Exception(
        when (code) {
            -1 -> "Chưa có API key OpenSubtitles — thêm trong Cài đặt ▸ Phụ đề trực tuyến."
            401 -> "OpenSubtitles từ chối API key hoặc tài khoản (401)."
            406, 429 -> "Hết lượt tải hôm nay trên OpenSubtitles. $detail"
            else -> "OpenSubtitles lỗi $code. $detail"
        }
    )

    companion object {
        private const val HOST = "api.opensubtitles.com"

        fun current(): OpenSubtitlesClient? {
            val key = AppSettings.openSubtitlesKey ?: return null
            val user = AppGraph.settings.openSubtitlesUser.value.takeIf { it.isNotBlank() }
            return OpenSubtitlesClient(key, user, AppGraph.secrets.get("opensubtitles.password"))
        }

        /** "vi,vie,en,eng" → ["en", "vi"]: two-letter codes, sorted as the API expects. */
        fun languageCodes(preferred: List<String>): List<String> =
            preferred.map { it.trim().lowercase() }.filter { it.length == 2 }.toSortedSet().toList()
    }

    suspend fun search(query: String, year: Int?, season: Int?, episode: Int?, languages: List<String>, hash: String?): List<Subtitle> =
        withContext(Dispatchers.IO) {
            val items = sortedMapOf("order_by" to "download_count", "order_direction" to "desc")
            val codes = languageCodes(languages)
            if (codes.isNotEmpty()) items["languages"] = codes.joinToString(",")
            if (hash != null) items["moviehash"] = hash
            if (query.isNotEmpty()) items["query"] = query.lowercase()
            if (year != null && season == null) items["year"] = "$year"
            if (season != null) items["season_number"] = "$season"
            if (episode != null) items["episode_number"] = "$episode"
            // The API wants parameters sorted and lower-case (otherwise it answers with a redirect).
            val url = HttpUrl.Builder().scheme("https").host(HOST).addPathSegments("api/v1/subtitles").apply {
                items.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val body = send(Request.Builder().url(url).build())
            LenientJson.decodeFromString(SearchResponse.serializer(), body).data.mapNotNull { item ->
                val file = item.attributes.files.firstOrNull() ?: return@mapNotNull null
                Subtitle(
                    id = item.id,
                    fileID = file.fileID,
                    language = item.attributes.language.orEmpty(),
                    release = item.attributes.release ?: file.fileName.orEmpty(),
                    downloads = item.attributes.downloadCount ?: 0,
                    hearingImpaired = item.attributes.hearingImpaired ?: false,
                    machineTranslated = (item.attributes.machineTranslated ?: false) || (item.attributes.aiTranslated ?: false),
                    hashMatch = item.attributes.moviehashMatch ?: false,
                )
            }
        }

    /** Downloads the subtitle file into cache/Subtitles and returns it. */
    suspend fun download(subtitle: Subtitle): File = withContext(Dispatchers.IO) {
        var host = HOST
        var token: String? = null
        if (username != null && !password.isNullOrEmpty()) {
            val login = login(username, password)
            token = login.token
            if (!login.baseUrl.isNullOrEmpty()) host = login.baseUrl
        }
        val request = Request.Builder()
            .url("https://$host/api/v1/download")
            .post(buildJsonObject { put("file_id", subtitle.fileID) }.toString().toRequestBody(JSON))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        val link = LenientJson.decodeFromString(DownloadResponse.serializer(), send(request))
        val data = Http.client.newCall(Request.Builder().url(link.link).build()).execute().use { response ->
            if (!response.isSuccessful) throw Failure(response.code, "")
            response.body?.bytes() ?: ByteArray(0)
        }
        val folder = File(AppGraph.context.cacheDir, "Subtitles").apply { mkdirs() }
        val name = (link.fileName ?: "${subtitle.fileID}.srt").replace('/', '_')
        File(folder, "${subtitle.fileID}-$name").apply { writeBytes(data) }
    }

    private fun login(username: String, password: String): LoginResponse {
        val request = Request.Builder()
            .url("https://$HOST/api/v1/login")
            .post(buildJsonObject { put("username", username); put("password", password) }.toString().toRequestBody(JSON))
            .build()
        return LenientJson.decodeFromString(LoginResponse.serializer(), send(request))
    }

    private fun send(request: Request): String {
        val signed = request.newBuilder()
            .header("Api-Key", apiKey)
            .header("User-Agent", "XT-Player v${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .build()
        return Http.client.newCall(signed).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { LenientJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
                throw Failure(response.code, message?.message ?: message?.errors?.joinToString(" ").orEmpty())
            }
            text
        }
    }

    @Serializable
    private data class SearchResponse(val data: List<Item> = emptyList()) {
        @Serializable
        data class Item(val id: String, val attributes: Attributes)

        @Serializable
        data class Attributes(
            val language: String? = null,
            @SerialName("download_count") val downloadCount: Int? = null,
            val release: String? = null,
            @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null,
            @SerialName("machine_translated") val machineTranslated: Boolean? = null,
            @SerialName("ai_translated") val aiTranslated: Boolean? = null,
            @SerialName("moviehash_match") val moviehashMatch: Boolean? = null,
            val files: List<FileRef> = emptyList(),
        )

        @Serializable
        data class FileRef(
            @SerialName("file_id") val fileID: Int,
            @SerialName("file_name") val fileName: String? = null,
        )
    }

    @Serializable
    private data class DownloadResponse(
        val link: String,
        @SerialName("file_name") val fileName: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class LoginResponse(val token: String, @SerialName("base_url") val baseUrl: String? = null)

    @Serializable
    private data class ErrorResponse(val message: String? = null, val errors: List<String>? = null)
}

private val JSON = "application/json".toMediaType()

/** The OpenSubtitles file hash: size + the 64-bit little-endian words of the first and last 64 KiB. */
object OpenSubtitlesHash {
    private const val CHUNK = 64 * 1024

    /** Blocking; call off the main thread. SMB (xtsmb://) and local files; null for anything else. */
    fun compute(location: String): String? {
        val uri = Uri.parse(location)
        return when (uri.scheme) {
            "xtsmb" -> runCatching { SmbFileReader.acquire(uri) }.getOrNull()?.let { reader ->
                try {
                    hash(reader.size) { offset -> reader.readFully(offset, CHUNK) }
                } finally {
                    reader.release()
                }
            }
            "content" -> runCatching {
                AppGraph.context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                        hash(channel.size()) { offset ->
                            val buffer = ByteBuffer.allocate(CHUNK)
                            channel.position(offset)
                            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                            buffer.array().copyOf(buffer.position())
                        }
                    }
                }
            }.getOrNull()
            null, "file" -> runCatching {
                RandomAccessFile(uri.path ?: location, "r").use { file ->
                    hash(file.length()) { offset ->
                        ByteArray(CHUNK).also { file.seek(offset); file.readFully(it) }
                    }
                }
            }.getOrNull()
            else -> null
        }
    }

    private fun hash(size: Long, read: (Long) -> ByteArray?): String? {
        if (size < CHUNK * 2L) return null
        val head = read(0) ?: return null
        val tail = read(size - CHUNK) ?: return null
        if (head.size != CHUNK || tail.size != CHUNK) return null
        var value = size
        for (data in listOf(head, tail)) {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 8) value += buffer.long
        }
        return "%016x".format(value)
    }
}
