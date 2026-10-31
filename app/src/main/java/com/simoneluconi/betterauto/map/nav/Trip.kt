package com.simoneluconi.betterauto.map.nav

import android.content.Context
import android.content.SharedPreferences
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.map.distanceM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * The trip: the stops in the order they are driven to, the destination last. One list that the car and the
 * phone both change (a passenger adds or moves a stop while the driver drives, the driver plans the trip before
 * leaving), followed by [Navigation] while the car screen is up. Kept on the phone: a trip broken off (the car
 * switched off for a break) goes on at the next connection, if it is not older than [KEEP_MS]; stops reached
 * during a real break are then dropped ([resume]).
 */
object Trip {
    private val _places = MutableStateFlow<List<Destination>>(emptyList())

    /** The stops, then the destination; empty with no trip. */
    val places: StateFlow<List<Destination>> = _places

    private val _offer = MutableStateFlow<Destination?>(null)

    /** A place picked in the car during a trip (said, a recent one), waiting for the driver: a stop, or the new destination. */
    val offer: StateFlow<Destination?> = _offer

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("trip", Context.MODE_PRIVATE)
        prefs = p
        Destinations.init(context)
        if (System.currentTimeMillis() - p.getLong(KEY_SAVED_AT, 0L) < KEEP_MS) {
            _places.value = runCatching { Destinations.decode(p.getString(KEY_PLACES, null)) }.getOrDefault(emptyList())
        } else {
            p.edit().clear().apply()
        }
    }

    /**
     * A trip last touched more than [KEEP_MS] ago is over: checked at every connection, not only when the app
     * starts, since the process may live on for days.
     */
    fun expireOld() {
        val p = prefs ?: return
        if (_places.value.isEmpty() || System.currentTimeMillis() - p.getLong(KEY_SAVED_AT, 0L) < KEEP_MS) return
        EventLog.log("Navigation: the trip left from before is too old, dropped")
        clear()
    }

    /** True when the trip was being driven when the car screen went away: at the next start, reached stops are dropped. */
    val interrupted: Boolean get() = prefs?.getBoolean(KEY_INTERRUPTED, false) == true

    /** A new trip to [place], instead of the one there was. */
    fun newDestination(place: Destination) {
        Destinations.add(place)
        change { listOf(place) }
        prefs?.edit()?.putBoolean(KEY_INTERRUPTED, false)?.apply()
        EventLog.log("Navigation: new destination")
    }

    /** Drives to [place] if there is no trip; during one, the driver chooses ([offer]). */
    fun choose(place: Destination) {
        if (_places.value.isEmpty()) newDestination(place) else _offer.value = place
    }

    fun clearOffer() {
        _offer.value = null
    }

    /**
     * Adds [stop] where it costs the least detour (never after the destination), from [fromLat], [fromLon] when the
     * position is known; the driver can move it afterwards. With no trip it becomes the destination. False when the
     * place is already in the trip (nothing changes).
     */
    fun addStop(stop: Destination, fromLat: Double?, fromLon: Double?): Boolean {
        if (_places.value.isEmpty()) {
            newDestination(stop)
            return true
        }
        if (_places.value.any { same(it, stop) }) return false
        Destinations.add(stop)
        change { current -> if (current.any { same(it, stop) }) null else withStop(current, stop, fromLat, fromLon) }
        EventLog.log("Navigation: stop added (${_places.value.size - 1} now)")
        return true
    }

    /** Takes the place at [index] out; the one before becomes the destination when it was the last. */
    fun removeAt(index: Int) {
        var size = 0
        val changed = change { list ->
            size = list.size
            if (index !in list.indices) null else list.filterIndexed { i, _ -> i != index }
        }
        if (changed) EventLog.log("Navigation: place ${index + 1} of $size removed")
    }

    /** Takes [place] out, wherever it is now (the list may have changed since it was shown). */
    fun remove(place: Destination) {
        val index = _places.value.indexOf(place)
        if (index >= 0) removeAt(index)
    }

    /** Moves the place at [index] one up ([delta] -1) or down (+1): moved last, it becomes the destination. */
    fun move(index: Int, delta: Int) {
        if (change { moved(it, index, delta) }) EventLog.log("Navigation: places reordered")
    }

    /** Moves the place at [from] to [to] in one change (one new route): put last, it becomes the destination. */
    fun moveTo(from: Int, to: Int) {
        if (from == to) return
        val changed = change { list ->
            if (from !in list.indices || to !in list.indices) null else list.toMutableList().apply { add(to, removeAt(from)) }
        }
        if (changed) EventLog.log("Navigation: places reordered")
    }

    /** The trip is over (arrived, or ended by the driver). */
    fun clear() {
        change { emptyList() }
        _offer.value = null
    }

    /**
     * The trip goes on after the car screen came back at [latitude], [longitude]: the places at its start that
     * were reached during a real break are dropped ([reachedDuringBreak]), and the trip is no longer a broken
     * off one. Returns how many were dropped.
     */
    fun resume(latitude: Double, longitude: Double, now: Long = System.currentTimeMillis()): Int {
        val p = prefs ?: return 0
        val breakMs = breakMs(now)
        val breakLat = p.getFloat(KEY_BREAK_LAT, Float.NaN).toDouble().takeUnless { it.isNaN() }
        val breakLon = p.getFloat(KEY_BREAK_LON, Float.NaN).toDouble().takeUnless { it.isNaN() }
        val dropped = reachedDuringBreak(_places.value, breakMs, breakLat, breakLon, latitude, longitude)
        if (dropped > 0) change { it.drop(dropped) }
        p.edit().putBoolean(KEY_INTERRUPTED, false).remove(KEY_BREAK_AT).remove(KEY_BREAK_LAT).remove(KEY_BREAK_LON).apply()
        return dropped
    }

    /**
     * How long ago the trip was broken off: its own time, not the last save, since a passenger may change the trip
     * on the phone during the break.
     */
    fun breakMs(now: Long = System.currentTimeMillis()): Long {
        val p = prefs ?: return 0L
        return now - p.getLong(KEY_BREAK_AT, p.getLong(KEY_SAVED_AT, now))
    }

    /** The car screen went away during the trip at [latitude], [longitude] (when known): remembered for [resume]. */
    fun interrupt(latitude: Double?, longitude: Double?) {
        val edit = prefs?.edit() ?: return
        val now = System.currentTimeMillis()
        edit.putLong(KEY_SAVED_AT, now).putLong(KEY_BREAK_AT, now).putBoolean(KEY_INTERRUPTED, true)
        if (latitude != null && longitude != null) {
            edit.putFloat(KEY_BREAK_LAT, latitude.toFloat()).putFloat(KEY_BREAK_LON, longitude.toFloat())
        } else {
            edit.remove(KEY_BREAK_LAT).remove(KEY_BREAK_LON)
        }
        edit.apply()
    }

    /**
     * Applies [compute] to the trip as it is now (null: no change) and saves the result; true when it changed.
     * Atomic: the guidance and the screens may change the trip at the same moment.
     */
    private fun change(compute: (List<Destination>) -> List<Destination>?): Boolean {
        var changed = false
        val list = _places.updateAndGet { current -> compute(current)?.also { changed = it != current } ?: current }
        if (!changed) return false
        val p = prefs ?: return true
        if (list.isEmpty()) {
            p.edit().clear().apply()
        } else {
            p.edit().putString(KEY_PLACES, Destinations.encode(list)).putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
        }
        return true
    }

    /**
     * How many places at the start of [places] were reached during a break of [breakMs]: only a real break (at
     * least [MIN_BREAK_MS]: not a screen rebuilt or a link dropped for a moment), and only places within
     * [REACHED_M] of where the car stopped ([breakLat], [breakLon], when known) and of where it is now.
     */
    internal fun reachedDuringBreak(
        places: List<Destination>,
        breakMs: Long,
        breakLat: Double?,
        breakLon: Double?,
        latitude: Double,
        longitude: Double,
    ): Int {
        if (breakMs < MIN_BREAK_MS) return 0
        fun near(d: Destination, lat: Double, lon: Double) = distanceM(lat, lon, d.latitude, d.longitude) < REACHED_M
        return places.takeWhile { d ->
            near(d, latitude, longitude) && (breakLat == null || breakLon == null || near(d, breakLat, breakLon))
        }.size
    }

    /** Where [stop] adds the least way: before each place in turn, from where the car is (when known). */
    internal fun withStop(places: List<Destination>, stop: Destination, fromLat: Double?, fromLon: Double?): List<Destination> {
        if (places.isEmpty()) return listOf(stop)
        fun d(aLat: Double, aLon: Double, b: Destination) = distanceM(aLat, aLon, b.latitude, b.longitude)
        var best = places.size - 1
        var bestCost = Double.MAX_VALUE
        for (i in places.indices) {
            val next = places[i]
            val cost = if (i == 0) {
                // Before the first place only with the car's position to measure the detour from
                if (fromLat == null || fromLon == null) continue
                d(fromLat, fromLon, stop) + d(stop.latitude, stop.longitude, next) - d(fromLat, fromLon, next)
            } else {
                val prev = places[i - 1]
                d(prev.latitude, prev.longitude, stop) + d(stop.latitude, stop.longitude, next) - d(prev.latitude, prev.longitude, next)
            }
            if (cost < bestCost) {
                bestCost = cost
                best = i
            }
        }
        return places.take(best) + stop + places.drop(best)
    }

    internal fun moved(places: List<Destination>, index: Int, delta: Int): List<Destination>? {
        val to = index + delta
        if (index !in places.indices || to !in places.indices) return null
        return places.toMutableList().apply { add(to, removeAt(index)) }
    }

    private fun same(a: Destination, b: Destination) = distanceM(a.latitude, a.longitude, b.latitude, b.longitude) < SAME_M

    private const val KEY_PLACES = "places"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_INTERRUPTED = "interrupted"
    private const val KEY_BREAK_AT = "break_at"
    private const val KEY_BREAK_LAT = "break_lat"
    private const val KEY_BREAK_LON = "break_lon"
    private const val KEEP_MS = 12 * 3600_000L
    private const val SAME_M = 50.0

    /** Shorter than this the screen was rebuilt or the link dropped for a moment: nothing was reached meanwhile. */
    internal const val MIN_BREAK_MS = 3 * 60_000L

    /** A place this close to the car, at the break and after it, was reached (a car park is rarely on the route's end point). */
    internal const val REACHED_M = 300.0
}
