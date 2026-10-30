package com.xuanthanhtnt.xtplayer.metadata

import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.AppSettings
import com.xuanthanhtnt.xtplayer.core.Http
import com.xuanthanhtnt.xtplayer.network.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

val LenientJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

/** TMDb API v3 client. Accepts either a v3 API key or a v4 read-access token (JWT, sent as Bearer). */
class TmdbClient(private val credential: String, private val language: String) {
    companion object {
        private const val BASE = "https://api.themoviedb.org/3/"

        fun current(): TmdbClient? {
            val credential = AppSettings.tmdbCredential ?: return null
            return TmdbClient(credential, AppGraph.settings.metadataLanguage.value)
        }

        /** What an image view loads for a stored path: a local video frame, or a TMDb image URL. */
        fun imageModel(path: String?, size: String = "w500"): Any? {
            if (path.isNullOrEmpty()) return null
            if (ThumbnailStore.isLocal(path)) return ThumbnailStore.file(path)
            return "https://image.tmdb.org/t/p/$size$path"
        }
    }

    suspend fun searchMovie(title: String, year: Int?): TmdbSearchResult? {
        if (year != null) search("search/movie", mapOf("query" to title, "year" to "$year")).firstOrNull()?.let { return it }
        return search("search/movie", mapOf("query" to title)).firstOrNull()
    }

    suspend fun searchShow(title: String, year: Int?): TmdbSearchResult? {
        if (year != null) search("search/tv", mapOf("query" to title, "first_air_date_year" to "$year")).firstOrNull()?.let { return it }
        return search("search/tv", mapOf("query" to title)).firstOrNull()
    }

    suspend fun movie(id: Int): TmdbDetails = details("movie/$id")

    suspend fun show(id: Int): TmdbDetails = details("tv/$id")

    suspend fun season(show: Int, number: Int): TmdbSeason =
        LenientJson.decodeFromString(TmdbSeason.serializer(), get("tv/$show/season/$number", emptyMap()))

    private suspend fun details(path: String): TmdbDetails {
        var details = LenientJson.decodeFromString(TmdbDetails.serializer(), get(path, mapOf("append_to_response" to "credits")))
        if (details.overview.isNullOrEmpty() && language != "en-US") {
            val english = LenientJson.decodeFromString(TmdbDetails.serializer(), get(path, emptyMap(), "en-US"))
            details = details.copy(overview = english.overview)
        }
        return details
    }

    private suspend fun search(path: String, query: Map<String, String>): List<TmdbSearchResult> =
        LenientJson.decodeFromString(TmdbPage.serializer(), get(path, query + ("include_adult" to "false"))).results

    private suspend fun get(path: String, query: Map<String, String>, language: String? = null): String = withContext(Dispatchers.IO) {
        val isToken = credential.startsWith("eyJ")
        val url = (BASE + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("language", language ?: this@TmdbClient.language)
            if (!isToken) addQueryParameter("api_key", credential)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (isToken) header("Authorization", "Bearer $credential")
        }.build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code != 200) throw RemoteException("Máy chủ trả về lỗi HTTP ${response.code}.")
            response.body?.string() ?: ""
        }
    }
}

@Serializable
data class TmdbPage(val results: List<TmdbSearchResult> = emptyList())

@Serializable
data class TmdbSearchResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
data class TmdbDetails(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int>? = null,
    val genres: List<Genre>? = null,
    val credits: Credits? = null,
) {
    @Serializable
    data class Genre(val name: String)

    @Serializable
    data class Credits(val cast: List<Person> = emptyList())

    @Serializable
    data class Person(
        val name: String,
        val character: String? = null,
        @SerialName("profile_path") val profilePath: String? = null,
    )

    val displayTitle: String? get() = title ?: name
    val year: Int? get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val episodes: List<Episode> = emptyList(),
) {
    @Serializable
    data class Episode(
        @SerialName("episode_number") val episodeNumber: Int,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("still_path") val stillPath: String? = null,
        @SerialName("air_date") val airDate: String? = null,
    )
}
