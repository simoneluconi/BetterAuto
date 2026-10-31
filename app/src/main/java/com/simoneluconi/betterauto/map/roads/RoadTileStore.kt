package com.simoneluconi.betterauto.map.roads

import android.content.Context
import com.simoneluconi.betterauto.EventLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Overture's road tiles for the map: read straight from Overture's public archive a tile at a time (a few
 * KB in the country, some hundred in a city centre) and kept on disk, so the roads driven every day cost no
 * data. Overture publishes a new archive every month and removes old ones: the newest is looked up now and
 * then, and again when the one in use is gone. Used only while a map is on screen. Not thread-safe: callers
 * take turns ([RoadInfo] does).
 */
class RoadTileStore(context: Context) {
    private val dir = File(context.cacheDir, "roads")
    private val prefs = context.getSharedPreferences("roads", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private var archive: Pair<String, PmTiles>? = null

    /** The roads of tile [x]/[y] at [OvertureRoads.ZOOM]; null when they can't be had now (no network). */
    fun roads(x: Int, y: Int): List<RoadSegment>? {
        val bytes = tileBytes(x, y, retry = true) ?: return null
        return if (bytes.isEmpty()) emptyList() else runCatching { OvertureRoads.parse(bytes, OvertureRoads.ZOOM, x, y) }
            .onFailure { EventLog.log("Map: road tile $x/$y unreadable: ${it.message}") }
            .getOrNull()
    }

    private fun tileBytes(x: Int, y: Int, retry: Boolean): ByteArray? {
        val release = release() ?: return null
        val file = File(dir, "$release/${OvertureRoads.ZOOM}_${x}_$y.mvt")
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return runCatching { file.readBytes() }.getOrNull()
        }
        return try {
            // An empty file remembers a tile with nothing in it (sea, a desert)
            val bytes = archive(release).tile(OvertureRoads.ZOOM, x, y) ?: ByteArray(0)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            trim()
            bytes
        } catch (e: GoneException) {
            // This month's archive replaced the one we knew: find it and try once more
            EventLog.log("Map: road archive $release is gone, looking for the new one")
            forgetRelease()
            if (retry) tileBytes(x, y, retry = false) else null
        } catch (e: IOException) {
            EventLog.log("Map: road tile $x/$y not loaded: ${e.message}")
            null
        }
    }

    private fun archive(release: String): PmTiles {
        archive?.takeIf { it.first == release }?.let { return it.second }
        val url = "$BUCKET/tiles/$release/transportation.pmtiles"
        return PmTiles(HttpRangeReader(client, url)).also { archive = release to it }
    }

    /** The newest archive's name ("2026-10-21.0"), looked up at most every few days. */
    private fun release(): String? {
        val known = prefs.getString(KEY_RELEASE, null)
        val checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L)
        if (known != null && System.currentTimeMillis() - checkedAt < RELEASE_CHECK_MS) return known
        val newest = runCatching { newestRelease() }.onFailure { EventLog.log("Map: road archive list not loaded: ${it.message}") }.getOrNull()
            ?: return known
        if (newest != known) {
            EventLog.log("Map: road archive $newest")
            // The old month's tiles go: they would never be read again
            dir.listFiles()?.filter { it.name != newest }?.forEach { it.deleteRecursively() }
        }
        prefs.edit().putString(KEY_RELEASE, newest).putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
        return newest
    }

    private fun forgetRelease() {
        archive = null
        prefs.edit().putLong(KEY_CHECKED_AT, 0L).apply()
    }

    private fun newestRelease(): String? {
        val request = Request.Builder().url("$BUCKET/?list-type=2&prefix=tiles/&delimiter=/").header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return releases(response.body?.string().orEmpty()).maxOrNull()
        }
    }

    /** Keeps the cache under [MAX_CACHE_BYTES], dropping the tiles read longest ago. */
    private fun trim() {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
        }
    }

    private class GoneException(message: String) : IOException(message)

    private class HttpRangeReader(private val client: OkHttpClient, private val url: String) : RangeReader {
        override fun read(offset: Long, length: Int): ByteArray {
            val request = Request.Builder().url(url)
                .header("Range", "bytes=$offset-${offset + length - 1}")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 404) throw GoneException("HTTP ${response.code}")
                // Anything but a partial answer (a server ignoring the range would send the whole, huge file) is
                // closed unread
                if (response.code != 206) throw IOException("HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("empty response")
                if (bytes.size != length) throw IOException("got ${bytes.size} bytes of $length")
                return bytes
            }
        }
    }

    companion object {
        private const val BUCKET = "https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com"
        private const val USER_AGENT = "BetterAuto (https://github.com/simoneluconi/BetterAuto)"
        private const val KEY_RELEASE = "release"
        private const val KEY_CHECKED_AT = "release_checked_at"
        private const val RELEASE_CHECK_MS = 3 * 24 * 3600_000L
        private const val MAX_CACHE_BYTES = 48L * 1024 * 1024

        /** The release folders in the bucket's listing: `<Prefix>tiles/2026-10-21.0/</Prefix>`. */
        internal fun releases(listing: String): List<String> =
            Regex("<Prefix>tiles/([0-9]{4}-[0-9]{2}-[0-9]{2}\\.[0-9]+)/</Prefix>").findAll(listing).map { it.groupValues[1] }.toList()
    }
}
