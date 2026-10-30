package com.xuanthanhtnt.xtplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.xuanthanhtnt.xtplayer.core.Http
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystems
import com.xuanthanhtnt.xtplayer.network.ServerRegistry

/**
 * Opens every location XT-Player plays: `xtsmb://` through [SmbDataSource], `xtdav://` through OkHttp with the
 * server's login and TLS policy, and everything else (content://, files, http(s), HLS) through Media3's default.
 */
class XtDataSource(private val context: Context) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        var spec = dataSpec
        val source: DataSource = when (uri.scheme) {
            RemoteFileSystems.SMB_SCHEME -> SmbDataSource()
            RemoteFileSystems.DAV_SCHEME -> {
                val server = ServerRegistry.get(uri.authority ?: "")
                    ?: throw DataSourceException("Không tìm thấy máy chủ WebDAV.", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
                spec = dataSpec.withUri(Uri.parse(RemoteFileSystems.webDavUrl(server, uri)))
                OkHttpDataSource.Factory(Http.forServer(server)).setUserAgent(Http.userAgent).createDataSource()
            }
            else -> DefaultDataSource.Factory(context, OkHttpDataSource.Factory(Http.client).setUserAgent(Http.userAgent))
                .createDataSource()
        }
        listeners.forEach(source::addTransferListener)
        current = source
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IllegalStateException("not opened")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = XtDataSource(context.applicationContext).also {
            it.addTransferListener(TrafficMeter)
        }
    }
}
