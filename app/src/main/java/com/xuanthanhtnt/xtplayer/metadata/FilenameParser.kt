package com.xuanthanhtnt.xtplayer.metadata

/** What can be learned from a release-style file name, before any TMDb lookup. */
data class ParsedMediaName(
    val title: String,
    val year: Int?,
    val kind: Kind,
    val resolution: String?,
    val dynamicRange: List<String>,
    val audio: List<String>,
) {
    sealed interface Kind {
        data object Movie : Kind
        data class Episode(val season: Int, val episode: Int) : Kind
    }

    /** "The Matrix (1999)" / "Severance · S02E05". */
    val displayTitle: String
        get() = when (val k = kind) {
            is Kind.Episode -> "%s · S%02dE%02d".format(title, k.season, k.episode)
            Kind.Movie -> if (year != null) "$title ($year)" else title
        }

    /** Compact chips for the UI, e.g. ["4K", "DV", "HDR10", "TrueHD Atmos"]. */
    val badges: List<String>
        get() = buildList {
            resolution?.let(::add)
            addAll(dynamicRange)
            audio.firstOrNull()?.let(::add)
        }
}

/**
 * Splits scene / Plex / Kodi style names: "Movie.Title.2019.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1-GRP.mkv",
 * "Show Name - S01E02 - Episode.mkv", "Show.Name.1x02.mkv", "Movie Title (2019).mkv".
 */
object FilenameParser {
    val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "wmv", "ts", "m2ts", "mts", "webm", "flv", "iso", "mpg", "mpeg", "vob", "ogm", "rmvb",
        "3gp",
    )

    val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub", "sup", "idx", "smi")

    private val episodePattern =
        Regex("""(?i)(?:^|[\s._\-\[(])s(\d{1,2})[\s._\-]?e(\d{1,3})(?:[\s._\-]?e\d{1,3})*(?=$|[\s._\-\])])""")
    private val crossPattern = Regex("""(?i)(?:^|[\s._\-\[(])(\d{1,2})x(\d{2,3})(?=$|[\s._\-\])])""")
    private val yearPattern = Regex("""(?:^|[\s._\-\[(])((?:19|20)\d{2})(?=$|[\s._\-\])])""")
    private val qualityPattern = Regex(
        """(?i)(?:^|[\s._\-\[(])(?:2160p|1080p|1080i|720p|576p|480p|4k|uhd|bluray|blu-ray|bdremux|remux|bdrip|brrip|web-?dl|webrip|hdtv|dvdrip|x26[45]|h\.?26[45]|hevc|avc|hdr10|dovi)(?=$|[\s._\-\])])"""
    )
    private val leadingTag = Regex("""^\s*[\[(][^\])]*[\])]\s*""")
    private val spaces = Regex("""\s+""")

    fun parse(rawName: String): ParsedMediaName {
        var name = rawName
        val dot = name.lastIndexOf('.')
        if (dot >= 0 && name.substring(dot + 1).lowercase() in videoExtensions) name = name.substring(0, dot)
        val lower = name.lowercase()

        var kind: ParsedMediaName.Kind = ParsedMediaName.Kind.Movie
        var titleEnd = name.length
        var year: Int? = null

        val episode = firstMatch(episodePattern, name) ?: firstMatch(crossPattern, name)
        if (episode != null) {
            kind = ParsedMediaName.Kind.Episode(
                season = episode.groups.getOrNull(0)?.toIntOrNull() ?: 0,
                episode = episode.groups.getOrNull(1)?.toIntOrNull() ?: 0,
            )
            titleEnd = episode.start
        }

        // Year: the last plausible 19xx/20xx token that is not at the very start (keeps "2001 A Space Odyssey 1968").
        val years = allMatches(yearPattern, name)
            .filter { it.start > 0 }
            .filter { it.start < titleEnd || titleEnd == name.length }
        years.lastOrNull()?.let { y ->
            year = y.groups.firstOrNull()?.toIntOrNull()
            if (y.start < titleEnd) titleEnd = y.start
        }

        if (titleEnd == name.length) {
            val quality = firstMatch(qualityPattern, name)
            if (quality != null && quality.start > 0) titleEnd = quality.start
        }

        var title = name.substring(0, titleEnd)
        title = title.replace(leadingTag, "")   // leading [Group] tag
        if (!title.contains(' ') || title.count { it == '.' || it == '_' } >= 2) {
            title = title.replace('.', ' ').replace('_', ' ')
        }
        title = title.replace(spaces, " ")
        title = title.trim { it in " -–—_.([{" }
        if (title.isEmpty()) title = name

        return ParsedMediaName(
            title = title,
            year = year,
            kind = kind,
            resolution = resolution(lower),
            dynamicRange = dynamicRange(lower),
            audio = audio(lower),
        )
    }

    // Tags

    private fun resolution(s: String): String? = when {
        has("""2160p|4k|uhd""", s) -> "4K"
        has("""1080[pi]""", s) -> "1080p"
        has("""720p""", s) -> "720p"
        has("""576p|480p|dvdrip""", s) -> "SD"
        else -> null
    }

    private fun dynamicRange(s: String): List<String> = buildList {
        if (has("""dv|dovi|dolby[\s._\-]?vision""", s)) add("DV")
        if (has("""hdr10\+|hdr10plus|hdr10p""", s)) add("HDR10+") else if (has("""hdr10|hdr""", s)) add("HDR10")
        if (has("""hlg""", s)) add("HLG")
    }

    private fun audio(s: String): List<String> {
        val out = mutableListOf<String>()
        val atmos = has("""atmos""", s)
        if (has("""truehd""", s)) out.add(if (atmos) "TrueHD Atmos" else "TrueHD")
        when {
            has("""dts[\s._\-]?x""", s) -> out.add("DTS:X")
            has("""dts[\s._\-]?hd(?:[\s._\-]?ma)?""", s) -> out.add("DTS-HD MA")
            has("""dts""", s) -> out.add("DTS")
        }
        if (has("""ddp|dd\+|eac3|e-ac-3""", s)) out.add(if (atmos && "TrueHD Atmos" !in out) "DD+ Atmos" else "DD+")
        if (has("""ac3|dd5[\s._]?1|dd2[\s._]?0""", s)) out.add("AC3")
        if (has("""flac""", s)) out.add("FLAC")
        if (out.isEmpty() && has("""aac""", s)) out.add("AAC")
        return out
    }

    // Regex helpers

    private class Match(val start: Int, val end: Int, val groups: List<String>)

    private val tokenCache = HashMap<String, Regex>()

    /** Digits may follow a token directly ("DDP5.1", "AAC2.0", "DTS5.1"). */
    private fun has(token: String, s: String): Boolean {
        val regex = synchronized(tokenCache) {
            tokenCache.getOrPut(token) { Regex("(?:^|[^a-z0-9])(?:$token)(?=\$|[^a-z])") }
        }
        return regex.containsMatchIn(s)
    }

    private fun firstMatch(regex: Regex, s: String): Match? = allMatches(regex, s).firstOrNull()

    private fun allMatches(regex: Regex, s: String): List<Match> = regex.findAll(s).map { m ->
        val groupRanges = (1 until m.groups.size).mapNotNull { m.groups[it]?.range }
        // A leading separator belongs to the boundary, not the token.
        var start = m.range.first
        if (start <= m.range.last && s[start] in " ._-[(" && (groupRanges.firstOrNull()?.let { it.first > start } != false)) {
            start += 1
        }
        Match(start, m.range.last + 1, groupRanges.map { s.substring(it) })
    }.toList()
}
