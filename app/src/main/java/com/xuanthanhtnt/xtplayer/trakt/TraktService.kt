package com.xuanthanhtnt.xtplayer.trakt

import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.AppSettings
import com.xuanthanhtnt.xtplayer.core.Http
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.MediaEntry
import com.xuanthanhtnt.xtplayer.metadata.FilenameParser
import com.xuanthanhtnt.xtplayer.metadata.ParsedMediaName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A movie or an episode as Trakt identifies it (TMDb id when the library has one, else title / year). */
sealed class TraktMedia {
    data class Movie(val title: String, val year: Int?, val tmdb: Int?) : TraktMedia()
    data class Episode(val show: String, val year: Int?, val tmdb: Int?, val season: Int, val number: Int) : TraktMedia()

    /** Scrobble body part: {"movie": …} or {"show": …, "episode": …}. */
    fun scrobbleObject(): Map<String, JsonElement> = when (this) {
        is Movie -> mapOf("movie" to obj(title, year, tmdb))
        is Episode -> mapOf(
            "show" to obj(show, year, tmdb),
            "episode" to buildJsonObject { put("season", season); put("number", number) },
        )
    }

    /** Same title/year/id for matching Trakt lists against the library. */
    val identity: String
        get() = when (this) {
            is Movie -> tmdb?.let { "m:tmdb:$it" } ?: "m:${normalize(title)}|${year ?: 0}"
            is Episode -> (tmdb?.let { "e:tmdb:$it" } ?: "e:${normalize(show)}") + ":$season:$number"
        }

    companion object {
        fun obj(title: String, year: Int?, tmdb: Int?): JsonObject = buildJsonObject {
            put("title", title)
            if (year != null) put("year", year)
            if (tmdb != null) put("ids", buildJsonObject { put("tmdb", tmdb) })
        }

        fun normalize(title: String): String =
            Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

        /** What a playable library file is on Trakt; files outside the library are recognised from their name. */
        suspend fun from(fileKey: String, fallbackName: String?): TraktMedia? {
            val dao = AppGraph.dao
            val file = dao.file(fileKey)
            if (file != null) {
                val item = dao.item(file.itemKey)
                if (item != null) {
                    if (item.kind == LibraryKind.SHOW && file.season != null && file.episode != null) {
                        return Episode(item.title, item.year, item.tmdbID, file.season, file.episode)
                    }
                    // Unmatched home videos / concerts would only create noise on Trakt.
                    if (item.tmdbID == null && item.year == null) return null
                    return Movie(item.title, item.year, item.tmdbID)
                }
            }
            val name = fallbackName ?: return null
            val parsed = FilenameParser.parse(name)
            return when (val kind = parsed.kind) {
                is ParsedMediaName.Kind.Episode -> Episode(parsed.title, null, null, kind.season, kind.episode)
                ParsedMediaName.Kind.Movie -> if (parsed.year != null) Movie(parsed.title, parsed.year, null) else null
            }
        }
    }
}

/**
 * Trakt.tv: device-code login (works on TV boxes), scrobbling, watched history both ways, and resume points
 * shared with other devices and apps (Infuse and the Apple XT-Player included) through /sync/playback.
 */
class TraktService {
    sealed class Phase {
        data object Disconnected : Phase()
        data class AwaitingCode(val code: String, val url: String) : Phase()
        data object Connected : Phase()
    }

    enum class ScrobbleAction(val path: String) { START("start"), PAUSE("pause"), STOP("stop") }

    @Serializable
    private data class Token(val access: String, val refresh: String, val expires: Long)

    private val phaseState = MutableStateFlow<Phase>(Phase.Disconnected)
    private val usernameState = MutableStateFlow("")
    private val statusState = MutableStateFlow("")
    private val syncing = MutableStateFlow(false)
    val phase: StateFlow<Phase> = phaseState
    val username: StateFlow<String> = usernameState
    val status: StateFlow<String> = statusState
    val isSyncing: StateFlow<Boolean> = syncing

    private var token: Token? = null
    private var loginJob: Job? = null
    private val prefs get() = AppGraph.settings.prefs

    init {
        AppGraph.secrets.get(TOKEN_ACCOUNT)?.let { raw ->
            runCatching { Json.decodeFromString(Token.serializer(), raw) }.getOrNull()?.let {
                token = it
                phaseState.value = Phase.Connected
                usernameState.value = prefs.getString(USERNAME_KEY, "") ?: ""
            }
        }
    }

    val isConnected: Boolean get() = phaseState.value == Phase.Connected
    val hasAppCredentials: Boolean get() = AppSettings.traktClient != null

    // Login

    fun startLogin() {
        val client = AppSettings.traktClient
        if (client == null) {
            statusState.value = "Nhập Client ID của ứng dụng Trakt trước."
            return
        }
        loginJob?.cancel()
        loginJob = AppGraph.scope.launch {
            try {
                val code = post("/oauth/device/code", buildJsonObject { put("client_id", client.first) })
                val deviceCode = code.string("device_code") ?: throw TraktError("Trakt không trả mã kích hoạt.")
                val userCode = code.string("user_code") ?: throw TraktError("Trakt không trả mã kích hoạt.")
                phaseState.value = Phase.AwaitingCode(userCode, code.string("verification_url") ?: "https://trakt.tv/activate")
                statusState.value = ""
                var interval = (code.int("interval") ?: 5).toLong()
                val deadline = System.currentTimeMillis() + (code.int("expires_in") ?: 600) * 1000L
                while (System.currentTimeMillis() < deadline && isActive) {
                    delay(interval * 1000)
                    val body = withSecret(buildJsonObject { put("code", deviceCode); put("client_id", client.first) }, client.second)
                    val (status, text) = raw("/oauth/device/token", "POST", body, authorized = false)
                    when (status) {
                        200 -> {
                            store(text)
                            phaseState.value = Phase.Connected
                            loadUsername()
                            sync()
                            return@launch
                        }
                        400 -> continue                      // the user has not entered the code yet
                        429 -> interval += 1                 // polling too fast
                        418 -> throw TraktError("Bạn đã từ chối kết nối trên Trakt.")
                        410 -> throw TraktError("Mã đã hết hạn — bấm Kết nối lại.")
                        else -> throw TraktError(httpMessage(status))
                    }
                }
                if (isActive) throw TraktError("Mã đã hết hạn — bấm Kết nối lại.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phaseState.value = if (token == null) Phase.Disconnected else Phase.Connected
                statusState.value = e.message ?: e.toString()
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        phaseState.value = if (token == null) Phase.Disconnected else Phase.Connected
    }

    fun disconnect() {
        val current = token
        val client = AppSettings.traktClient
        if (current != null && client != null) {
            AppGraph.scope.launch {
                runCatching {
                    raw("/oauth/revoke", "POST", withSecret(buildJsonObject {
                        put("token", current.access); put("client_id", client.first)
                    }, client.second), authorized = false)
                }
            }
        }
        token = null
        AppGraph.secrets.set(TOKEN_ACCOUNT, null)
        prefs.edit().remove(USERNAME_KEY).apply()
        usernameState.value = ""
        phaseState.value = Phase.Disconnected
        statusState.value = ""
    }

    // Scrobble

    /** `progress` 0…100. A stop under 80 % keeps a resume point on Trakt; at 80 % or more the item is watched. */
    fun scrobble(action: ScrobbleAction, media: TraktMedia, progress: Double) {
        if (!isConnected || !AppGraph.settings.traktScrobble.value || !progress.isFinite()) return
        val body = JsonObject(media.scrobbleObject() + ("progress" to JsonPrimitive(min(100.0, max(0.0, (progress * 100).roundToLong() / 100.0)))))
        AppGraph.scope.launch { runCatching { raw("/scrobble/${action.path}", "POST", body, authorized = true) } }
    }

    // History

    fun setWatched(watched: Boolean, media: List<TraktMedia>) {
        if (!isConnected || media.isEmpty()) return
        val body = historyBody(media)
        AppGraph.scope.launch {
            runCatching { raw(if (watched) "/sync/history" else "/sync/history/remove", "POST", body, authorized = true) }
        }
    }

    private fun historyBody(media: List<TraktMedia>): JsonObject {
        val movies = mutableListOf<JsonObject>()
        val shows = linkedMapOf<String, Pair<JsonObject, MutableMap<Int, MutableList<Int>>>>()
        for (item in media) {
            when (item) {
                is TraktMedia.Movie -> movies += TraktMedia.obj(item.title, item.year, item.tmdb)
                is TraktMedia.Episode -> {
                    val key = item.tmdb?.toString() ?: TraktMedia.normalize(item.show)
                    val entry = shows.getOrPut(key) { TraktMedia.obj(item.show, item.year, item.tmdb) to mutableMapOf() }
                    entry.second.getOrPut(item.season) { mutableListOf() } += item.number
                }
            }
        }
        return buildJsonObject {
            put("movies", JsonArray(movies))
            put("shows", buildJsonArray {
                for ((show, seasons) in shows.values) {
                    add(JsonObject(show + ("seasons" to buildJsonArray {
                        for ((number, episodes) in seasons.toSortedMap()) {
                            add(buildJsonObject {
                                put("number", number)
                                put("episodes", buildJsonArray { episodes.forEach { add(buildJsonObject { put("number", it) }) } })
                            })
                        }
                    })))
                }
            })
        }
    }

    // Sync

    /** Pulls resume points and watched history into the library and pushes local "watched" Trakt lacks. */
    suspend fun sync(): Unit = withContext(Dispatchers.IO) {
        if (!isConnected || !syncing.compareAndSet(false, true)) return@withContext
        statusState.value = "Đang đồng bộ Trakt…"
        try {
            val index = TraktLibraryIndex.build()
            val playback = getList("/sync/playback")
            val watchedMovies = getList("/sync/watched/movies")
            val watchedShows = getList("/sync/watched/shows")

            var resumed = 0
            for (item in playback) {
                val media = media(item) ?: continue
                val progress = item.double("progress") ?: continue
                val pausedAt = date(item.string("paused_at")) ?: continue
                for (key in index.keys(media)) if (applyResume(key, progress, pausedAt, index)) resumed++
            }

            val remoteWatched = mutableSetOf<String>()
            var marked = 0
            for (item in watchedMovies) {
                val media = (item["movie"] as? JsonObject)?.let(::movie) ?: continue
                remoteWatched += media.identity
                val at = date(item.string("last_watched_at")) ?: 0L
                for (key in index.keys(media)) if (markWatched(key, at, index)) marked++
            }
            for (item in watchedShows) {
                val show = item["show"] as? JsonObject ?: continue
                val seasons = item["seasons"] as? JsonArray ?: continue
                for (season in seasons.mapNotNull { it as? JsonObject }) {
                    val number = season.int("number") ?: continue
                    val episodes = season["episodes"] as? JsonArray ?: continue
                    for (episode in episodes.mapNotNull { it as? JsonObject }) {
                        val media = episode(show, number, episode.int("number") ?: continue) ?: continue
                        remoteWatched += media.identity
                        val at = date(episode.string("last_watched_at")) ?: 0L
                        for (key in index.keys(media)) if (markWatched(key, at, index)) marked++
                    }
                }
            }

            // Local "watched" that Trakt does not have yet.
            val missing = AppGraph.dao.entries().filter { it.isWatched }.mapNotNull { index.media[it.key] }
                .filter { it.identity !in remoteWatched }.distinct()
            if (missing.isNotEmpty()) setWatched(true, missing)

            statusState.value = "Đã đồng bộ: $resumed điểm xem tiếp, $marked mục đã xem" +
                if (missing.isEmpty()) "" else ", gửi ${missing.size} lên Trakt"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusState.value = e.message ?: e.toString()
        } finally {
            syncing.value = false
        }
    }

    /** Takes Trakt's resume point when it is newer than what this device saved. */
    private suspend fun applyResume(key: String, progress: Double, pausedAt: Long, index: TraktLibraryIndex): Boolean {
        val entry = AppGraph.dao.entry(key)
        if (entry != null && entry.lastPlayedAt >= pausedAt) return false
        val duration = if ((entry?.duration ?: 0.0) > 1) entry!!.duration else (index.runtime[key] ?: 0) * 60.0
        if (duration <= 0) return false
        val target = entry ?: MediaEntry(key = key, title = index.titles[key] ?: key, uri = key, isLocalFile = false)
        AppGraph.dao.upsertEntry(
            target.copy(duration = duration, position = duration * min(1.0, max(0.0, progress / 100)), lastPlayedAt = pausedAt)
        )
        return true
    }

    private suspend fun markWatched(key: String, watchedAt: Long, index: TraktLibraryIndex): Boolean {
        val entry = AppGraph.dao.entry(key)
        if (entry != null && (entry.isWatched || entry.lastPlayedAt > watchedAt)) return false
        val target = entry ?: MediaEntry(key = key, title = index.titles[key] ?: key, uri = key, isLocalFile = false)
        val duration = if (target.duration <= 0) max(1.0, (index.runtime[key] ?: 0) * 60.0) else target.duration
        AppGraph.dao.upsertEntry(target.copy(duration = duration, position = duration, lastPlayedAt = watchedAt))
        return true
    }

    // Parsing

    private fun media(item: JsonObject): TraktMedia? {
        (item["movie"] as? JsonObject)?.let { return movie(it) }
        val show = item["show"] as? JsonObject ?: return null
        val episode = item["episode"] as? JsonObject ?: return null
        return episode(show, episode.int("season") ?: return null, episode.int("number") ?: return null)
    }

    private fun movie(obj: JsonObject): TraktMedia? {
        val title = obj.string("title") ?: return null
        return TraktMedia.Movie(title, obj.int("year"), (obj["ids"] as? JsonObject)?.int("tmdb"))
    }

    private fun episode(show: JsonObject, season: Int, number: Int): TraktMedia? {
        val title = show.string("title") ?: return null
        return TraktMedia.Episode(title, show.int("year"), (show["ids"] as? JsonObject)?.int("tmdb"), season, number)
    }

    private fun date(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    // HTTP

    private class TraktError(message: String) : Exception(message)

    private fun httpMessage(code: Int) = when (code) {
        401 -> "Trakt: phiên đăng nhập hết hạn — kết nối lại."
        403 -> "Trakt từ chối Client ID (403) — kiểm tra lại ứng dụng Trakt."
        400 -> "Trakt từ chối yêu cầu (400) — nếu ứng dụng Trakt không có Client Secret, hãy xoá ô Client Secret."
        else -> "Trakt lỗi $code."
    }

    private suspend fun loadUsername() {
        val settings = runCatching { getObject("/users/settings") }.getOrNull() ?: return
        val name = (settings["user"] as? JsonObject)?.string("username") ?: return
        usernameState.value = name
        prefs.edit().putString(USERNAME_KEY, name).apply()
    }

    private fun store(body: String) {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val access = json?.string("access_token")
        val refresh = json?.string("refresh_token")
        if (access == null || refresh == null) throw TraktError("Trakt trả token không hợp lệ.")
        val created = json.long("created_at")?.times(1000) ?: System.currentTimeMillis()
        val expiresIn = json.long("expires_in") ?: 7_776_000L
        val token = Token(access, refresh, created + expiresIn * 1000)
        this.token = token
        AppGraph.secrets.set(TOKEN_ACCOUNT, Json.encodeToString(Token.serializer(), token))
    }

    /** Older Trakt apps have a client secret; PKCE apps (no secret) must not send one ("invalid_client"). */
    private fun withSecret(body: JsonObject, secret: String?): JsonObject =
        if (secret == null) body else JsonObject(body + ("client_secret" to JsonPrimitive(secret)))

    /**
     * Refreshes the access token when less than an hour is left (also once it has expired: the refresh token
     * stays valid). Trakt apps may be registered with a localhost or the out-of-band redirect URI.
     */
    private suspend fun validAccessToken(): String {
        val current = token ?: throw TraktError(httpMessage(401))
        val client = AppSettings.traktClient
        if (current.expires - System.currentTimeMillis() >= 3_600_000 || client == null) return current.access
        var status = 0
        for (redirect in listOf("http://localhost", "http://127.0.0.1", "urn:ietf:wg:oauth:2.0:oob")) {
            val (code, text) = raw("/oauth/token", "POST", withSecret(buildJsonObject {
                put("refresh_token", current.refresh); put("client_id", client.first)
                put("redirect_uri", redirect); put("grant_type", "refresh_token")
            }, client.second), authorized = false)
            if (code == 200) {
                store(text)
                return token?.access ?: current.access
            }
            status = code
            // 400 / 401 can mean a redirect URI the app was not registered with: try the next one.
            if (code != 400 && code != 401) break
        }
        if (status == 401 || status == 400) disconnect()
        throw TraktError(httpMessage(status))
    }

    private suspend fun getList(path: String): List<JsonObject> {
        val (status, text) = raw(path, "GET", null, authorized = true)
        if (status != 200) throw TraktError(httpMessage(status))
        return Json.parseToJsonElement(text).jsonArray.mapNotNull { it as? JsonObject }
    }

    private suspend fun getObject(path: String): JsonObject {
        val (status, text) = raw(path, "GET", null, authorized = true)
        if (status != 200) throw TraktError(httpMessage(status))
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject {
        val (status, text) = raw(path, "POST", body, authorized = false)
        if (status !in 200..299) throw TraktError(httpMessage(status))
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun raw(path: String, method: String, body: JsonObject?, authorized: Boolean): Pair<Int, String> {
        val client = AppSettings.traktClient ?: throw TraktError("Nhập Client ID của ứng dụng Trakt trước.")
        val bearer = if (authorized) validAccessToken() else null
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(API + path)
                .header("Content-Type", "application/json")
                .header("trakt-api-version", "2")
                .header("trakt-api-key", client.first)
                .apply {
                    if (bearer != null) header("Authorization", "Bearer $bearer")
                    if (body != null) method(method, body.toString().toRequestBody("application/json".toMediaType()))
                    else method(method, null)
                }
                .build()
            Http.client.newCall(request).execute().use { response -> response.code to (response.body?.string().orEmpty()) }
        }
    }

    private companion object {
        const val API = "https://api.trakt.tv"
        const val TOKEN_ACCOUNT = "trakt.token"
        const val USERNAME_KEY = "trakt.username"
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** The library seen from Trakt: which file keys a Trakt movie / episode is, plus titles and runtimes. */
class TraktLibraryIndex private constructor() {
    private val byIdentity = mutableMapOf<String, MutableList<String>>()
    /** file key → Trakt media (for pushing local "watched"). */
    val media = mutableMapOf<String, TraktMedia>()
    val titles = mutableMapOf<String, String>()
    /** file key → minutes. */
    val runtime = mutableMapOf<String, Int>()

    companion object {
        suspend fun build(): TraktLibraryIndex {
            val index = TraktLibraryIndex()
            val items = AppGraph.dao.items().associateBy { it.key }
            for (file in AppGraph.dao.files()) {
                val item = items[file.itemKey] ?: continue
                val forms = mutableListOf<TraktMedia>()
                if (item.kind == LibraryKind.SHOW && file.season != null && file.episode != null) {
                    forms += TraktMedia.Episode(item.title, item.year, item.tmdbID, file.season, file.episode)
                    if (item.tmdbID != null) forms += TraktMedia.Episode(item.title, item.year, null, file.season, file.episode)
                } else if (item.kind == LibraryKind.MOVIE) {
                    forms += TraktMedia.Movie(item.title, item.year, item.tmdbID)
                    if (item.tmdbID != null) forms += TraktMedia.Movie(item.title, item.year, null)
                }
                val primary = forms.firstOrNull() ?: continue
                if (item.tmdbID != null || item.year != null || item.kind == LibraryKind.SHOW) index.media[file.key] = primary
                for (form in forms) index.byIdentity.getOrPut(form.identity) { mutableListOf() } += file.key
                index.titles[file.key] = item.title
                item.runtime?.let { index.runtime[file.key] = it }
            }
            return index
        }
    }

    /** Library files for a Trakt item: by TMDb id first, then by title and year. */
    fun keys(media: TraktMedia): List<String> {
        byIdentity[media.identity]?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (media) {
            is TraktMedia.Movie -> if ((media.tmdb ?: 0) > 0) byIdentity[media.copy(tmdb = null).identity].orEmpty() else emptyList()
            is TraktMedia.Episode -> if ((media.tmdb ?: 0) > 0) byIdentity[media.copy(tmdb = null).identity].orEmpty() else emptyList()
        }
    }
}
