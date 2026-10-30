package com.xuanthanhtnt.xtplayer.network

import android.util.Xml
import com.xuanthanhtnt.xtplayer.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

/** PROPFIND-based browsing (Depth: 1). Basic/Digest login and self-signed TLS come from [Http.forServer]. */
class WebDavFileSystem(private val server: ServerCredentials) : RemoteFileSystem {
    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        var directory = path.ifEmpty { server.basePath }.ifEmpty { "/" }
        if (!directory.startsWith("/")) directory = "/$directory"
        if (!directory.endsWith("/")) directory += "/"

        val encoded = directory.split('/').joinToString("/") { android.net.Uri.encode(it) }
        val request = Request.Builder()
            .url(server.httpBase + encoded)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        val items = Http.forServer(server).newCall(request).execute().use { response ->
            if (response.code != 207 && response.code != 200) throw RemoteException("Máy chủ trả về lỗi HTTP ${response.code}.")
            parse(response.body?.byteStream() ?: throw RemoteException("Máy chủ không trả dữ liệu."))
        }
        val self = normalize(directory)
        items
            .filter { normalize(it.path) != self }
            .filter { !it.name.startsWith(".") && !it.name.startsWith("@") && it.name != "#recycle" }
    }

    private fun normalize(path: String): String {
        var p = path
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    private class Entry {
        var href = ""
        var displayName = ""
        var length = 0L
        var isCollection = false
        var modified: Long? = null
    }

    /** Parses a WebDAV multistatus body into items. */
    private fun parse(stream: InputStream): List<RemoteItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)
        val entries = mutableListOf<Entry>()
        var current: Entry? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (parser.name) {
                        "response" -> current = Entry()
                        "collection" -> current?.isCollection = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    when (parser.name) {
                        "href" -> current?.href = value
                        "displayname" -> current?.displayName = value
                        "getcontentlength" -> current?.length = value.toLongOrNull() ?: 0
                        "getlastmodified" -> current?.modified = runCatching { dateFormat().parse(value)?.time }.getOrNull()
                        "response" -> {
                            current?.let { entries.add(it) }
                            current = null
                        }
                    }
                    text.setLength(0)
                }
            }
            event = parser.next()
        }
        return entries.mapNotNull { entry ->
            var path = entry.href
            if (path.lowercase().startsWith("http")) path = runCatching { URI(path).rawPath }.getOrDefault(path)
            path = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
            val trimmed = path.trimEnd('/')
            val name = entry.displayName.ifEmpty { trimmed.substringAfterLast('/') }
            if (name.isEmpty()) return@mapNotNull null
            RemoteItem(name, trimmed.ifEmpty { "/" }, entry.isCollection, entry.length, entry.modified)
        }
    }

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        fun dateFormat() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    }
}
