package com.simoneluconi.betterauto.map.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Valhalla server the routes come from: the public demonstration one (FOSSGIS: fair use, for trying only,
 * the trip's places go to it), or the driver's own, set on the phone ([com.simoneluconi.betterauto.settings.LauncherSettings.routeServer]).
 */
object RouteServer {
    const val DEMO = "https://valhalla1.openstreetmap.de"

    /** Valhalla's own port, served without TLS: what a server at home usually is. */
    private const val VALHALLA_PORT = 8002

    /** Where to ask for a route on [base] (null: the demo server). */
    fun routeUrl(base: String?) = "${base ?: DEMO}/route"

    /**
     * What the driver typed, as a base URL ("https://host[:port][/path]", no "/route"), or null if it is not one.
     * Without a scheme: http for Valhalla's port 8002, https otherwise. A path stays: a server behind a proxy.
     */
    fun normalize(input: String): String? {
        val text = input.trim().trimEnd('/').removeSuffix("/route").trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = when {
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
            Regex(":$VALHALLA_PORT(/|$)").containsMatchIn(text) -> "http://$text"
            else -> "https://$text"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        val port = if (url.port == if (url.scheme == "https") 443 else 80) "" else ":${url.port}"
        val host = url.host.let { if (':' in it) "[$it]" else it }
        val path = url.encodedPath.trimEnd('/')
        return "${url.scheme}://$host$port$path"
    }

    /** The host alone, to show which server is in use. */
    fun host(base: String): String = base.toHttpUrlOrNull()?.host ?: base

    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    /** Whether [base] answers like Valhalla: its /status names a version (null when it does not). */
    suspend fun check(base: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$base/status")
                .header("User-Agent", "BetterAuto (https://github.com/simoneluconi/BetterAuto)")
                .build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                versionOf(r.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /** The version in Valhalla's /status answer, or null if it is not one. */
    internal fun versionOf(json: String): String? =
        runCatching { JSONObject(json).optString("version").takeIf { it.isNotBlank() } }.getOrNull()
}
