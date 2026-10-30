package com.xuanthanhtnt.xtplayer.core

import com.xuanthanhtnt.xtplayer.BuildConfig
import com.xuanthanhtnt.xtplayer.network.ServerCredentials
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object Http {
    val userAgent = "XT-Player-Android/${BuildConfig.VERSION_NAME}"

    /** Shared client for public APIs (TMDb, Trakt, OpenSubtitles, GitHub). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", userAgent).build() else request
            )
        }
        .build()

    private val serverClients = ConcurrentHashMap<ServerCredentials, OkHttpClient>()

    /** A NAS / WebDAV server: Basic (pre-emptive) or Digest login, and — when allowed — self-signed TLS. */
    fun forServer(server: ServerCredentials): OkHttpClient = serverClients.getOrPut(server) {
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .apply {
                if (server.allowInsecureTLS) {
                    val trustAll = TrustAll()
                    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
                    sslSocketFactory(ssl.socketFactory, trustAll)
                    hostnameVerifier { _, _ -> true }
                }
                if (server.username.isNotEmpty()) {
                    addInterceptor(PreemptiveBasic(server.username, server.password))
                    authenticator(DigestAuthenticator(server.username, server.password))
                }
            }
            .build()
    }

    fun forget(serverID: String) {
        serverClients.keys.filter { it.id == serverID }.forEach { serverClients.remove(it) }
    }

    private class TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class PreemptiveBasic(user: String, password: String) : Interceptor {
        private val header = Credentials.basic(user, password, Charsets.UTF_8)
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            return chain.proceed(
                if (request.header("Authorization") == null) request.newBuilder().header("Authorization", header).build() else request
            )
        }
    }

    /** RFC 2617 Digest (MD5, qop=auth) for WebDAV servers that refuse Basic. */
    private class DigestAuthenticator(private val user: String, private val password: String) : Authenticator {
        private val random = SecureRandom()

        override fun authenticate(route: Route?, response: Response): Request? {
            if (priorResponses(response) >= 2) return null
            val challenge = response.headers("WWW-Authenticate").firstOrNull { it.startsWith("Digest", ignoreCase = true) }
                ?: return null
            val params = parse(challenge.substring(6))
            val realm = params["realm"] ?: ""
            val nonce = params["nonce"] ?: return null
            val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            val request = response.request
            val uri = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
            val ha1 = md5("$user:$realm:$password")
            val ha2 = md5("${request.method}:$uri")
            val nc = "00000001"
            val cnonce = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val digest = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
            val header = buildString {
                append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$digest\"")
                params["opaque"]?.let { append(", opaque=\"$it\"") }
                params["algorithm"]?.let { append(", algorithm=$it") }
                if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            }
            return request.newBuilder().header("Authorization", header).build()
        }

        private fun priorResponses(response: Response): Int {
            var count = 0
            var prior = response.priorResponse
            while (prior != null) {
                count++
                prior = prior.priorResponse
            }
            return count
        }

        private fun parse(text: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""").findAll(text).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
            }
            return out
        }

        private fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
