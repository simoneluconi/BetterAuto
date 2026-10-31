package com.simoneluconi.betterauto.map.nav

import android.content.Context
import android.location.Geocoder
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.map.MapLocation
import com.simoneluconi.betterauto.map.distanceM
import com.simoneluconi.betterauto.smarthome.SmartHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** A place found by a search: a [Destination] and how far it is from the car, when known. */
data class PlaceResult(val destination: Destination, val distanceM: Double?)

/**
 * Finds places by name or address: shops, restaurants, cinemas ("Roadhouse", "Cinema Giometti") as well as
 * streets and towns. Photon (OpenStreetMap, https://photon.komoot.io) first, nearer places first; the phone's
 * own geocoder when Photon has nothing or can't be reached. Also reads what another app shares: map links,
 * coordinates, an address in a message, a contact card.
 */
object PlaceSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Places matching [query], best first, near the car when its position is known. */
    suspend fun search(context: Context, query: String, limit: Int = 8): List<PlaceResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        home(q)?.let { return@withContext listOf(PlaceResult(it, distanceFromCar(it))) }
        val near = MapLocation.fix.value
        val found = runCatching { photon(q, near?.latitude, near?.longitude, limit) }
            .onFailure { EventLog.log("Navigation: place search failed (${it.message}), trying the phone's geocoder") }
            .getOrNull().orEmpty()
            .ifEmpty { geocoder(context, q, limit) }
        found.map { PlaceResult(it, distanceFromCar(it)) }
    }

    /** "Casa" / "home": the home place of the linked smart home, when there is one. */
    private fun home(query: String): Destination? {
        if (query.lowercase() !in HomeWords) return null
        val place = SmartHome.linked.value.firstNotNullOfOrNull { it.catalog.value?.home } ?: return null
        return Destination(place.name, null, place.latitude, place.longitude)
    }

    private fun photon(query: String, lat: Double?, lon: Double?, limit: Int): List<Destination> {
        val url = PHOTON_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .apply {
                // Nearer places first: the driver's "Roadhouse" is the nearest one
                if (lat != null && lon != null) addQueryParameter("lat", "%.5f".format(java.util.Locale.ROOT, lat)).addQueryParameter("lon", "%.5f".format(java.util.Locale.ROOT, lon))
            }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return parsePhoton(response.body?.string().orEmpty())
        }
    }

    /** Photon's GeoJSON answer into destinations: the name (or the street) and a short address under it. */
    internal fun parsePhoton(json: String): List<Destination> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val p = f.optJSONObject("properties") ?: return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val town = p.optString("city").ifBlank { p.optString("town") }.ifBlank { p.optString("village") }.ifBlank { p.optString("county") }
            val name = p.optString("name").ifBlank { street }.ifBlank { town }.ifBlank { return@mapNotNull null }
            val address = listOf(street.takeIf { it != name }, town.takeIf { it != name }).filter { !it.isNullOrBlank() }.joinToString(", ").ifBlank { null }
            Destination(name, address, c.getDouble(1), c.getDouble(0))
        }
    }

    @Suppress("DEPRECATION") // the blocking call, on an IO thread: the listener version needs API 33
    private fun geocoder(context: Context, query: String, limit: Int): List<Destination> = runCatching {
        if (!Geocoder.isPresent()) return emptyList()
        Geocoder(context).getFromLocationName(query, limit).orEmpty().map { a ->
            val line = a.getAddressLine(0)
            Destination(a.featureName?.takeIf { it.any(Char::isLetter) } ?: line ?: query, line, a.latitude, a.longitude)
        }
    }.getOrDefault(emptyList())

    private fun distanceFromCar(d: Destination): Double? =
        MapLocation.fix.value?.let { distanceM(it.latitude, it.longitude, d.latitude, d.longitude) }

    /**
     * Where something shared points: a `geo:` or `google.navigation:` link, a map link (short ones followed to the
     * full address, which holds the coordinates), coordinates in the text, or else the text searched as an address.
     */
    suspend fun resolve(context: Context, shared: String, title: String? = null): Destination? = withContext(Dispatchers.IO) {
        val text = shared.trim()
        if (text.isEmpty()) return@withContext null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val link = Regex("""(https?://\S+|geo:\S+|google\.navigation:\S+)""").find(text)?.value
        // Google Maps shares "Place name\nhttps://maps.app.goo.gl/..."
        val label = title?.takeIf { it.isNotBlank() } ?: lines.firstOrNull { !it.contains("://") && !it.startsWith("geo:") }
        if (link != null) {
            val target = if (link.startsWith("http")) runCatching { followLink(link) }.getOrNull() ?: link else link
            coordinatesIn(target)?.let { (lat, lon) ->
                return@withContext Destination(label ?: placeNameIn(target) ?: "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon), null, lat, lon)
            }
            // A link with a place name but no coordinates: search the name
            (queryIn(target) ?: placeNameIn(target))?.let { name -> return@withContext search(context, name, 1).firstOrNull()?.destination }
        }
        coordinatesIn(text)?.let { (lat, lon) -> return@withContext Destination(label ?: "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon), null, lat, lon) }
        // Plain text: an address or a place name
        search(context, lines.joinToString(", "), 1).firstOrNull()?.destination
    }

    /** The address a short map link leads to. */
    private fun followLink(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val final = response.request.url.toString()
            // The European consent page carries the real address in "continue"
            return runCatching { response.request.url.queryParameter("continue") }.getOrNull() ?: final
        }
    }

    /** Coordinates in a map link or a text: a place's own pin first, then the view's centre, then any "lat, lon". */
    internal fun coordinatesIn(text: String): Pair<Double, Double>? {
        val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        val patterns = listOf(
            Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)"""),
            Regex("""geo:(-?\d+\.?\d*),(-?\d+\.?\d*)"""),
            Regex("""[?&](?:q|query|ll|daddr|destination)=(-?\d+\.\d+),\s*(-?\d+\.\d+)"""),
            Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)"""),
            Regex("""google\.navigation:q=(-?\d+\.\d+),(-?\d+\.\d+)"""),
            Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})(?![\d.])"""),
        )
        for (p in patterns) {
            val m = p.find(decoded) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            // geo:0,0?q=address means "no coordinates"
            if (lat == 0.0 && lon == 0.0) continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }

    /** "Piazza del Campo" from ".../maps/place/Piazza+del+Campo/@...". */
    internal fun placeNameIn(url: String): String? =
        Regex("""/place/([^/@?]+)""").find(url)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.ifBlank { null }

    /** The searched text of a `geo:0,0?q=...` or `?q=...` link. */
    internal fun queryIn(url: String): String? =
        Regex("""[?&](?:q|query|daddr|destination)=([^&]+)""").find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.takeIf { it.isNotBlank() && coordinatesIn("geo:$it") == null }

    /** An address in a contact card (vCard ADR: post box; extended; street; town; region; postcode; country). */
    internal fun vCardAddress(vcard: String): Pair<String?, String>? {
        val unfolded = vcard.replace(Regex("\r?\n[ \t]"), "")
        val name = Regex("""(?m)^FN[^:]*:(.+)$""").find(unfolded)?.groupValues?.get(1)?.trim()
        val adr = Regex("""(?m)^(?:item\d+\.)?ADR[^:]*:(.+)$""").find(unfolded)?.groupValues?.get(1) ?: return null
        val parts = adr.split(';').map { it.replace("\\,", ",").trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return name to parts.joinToString(", ")
    }

    private const val PHOTON_URL = "https://photon.komoot.io/api/"
    private const val USER_AGENT = "BetterAuto (https://github.com/simoneluconi/BetterAuto)"

    /** What "home" is called in a voice command or a search, in the languages of the app. */
    private val HomeWords = setOf("casa", "a casa", "home")
}
