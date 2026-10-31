package com.simoneluconi.betterauto.map.nav

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A place to drive to: what to call it, its address when known, where it is. */
data class Destination(val name: String, val address: String?, val latitude: Double, val longitude: Double)

/** The recent destinations and stops, kept on the phone: the last [MAX_RECENTS], the newest first. */
object Destinations {
    private val _recents = MutableStateFlow<List<Destination>>(emptyList())
    val recents: StateFlow<List<Destination>> = _recents

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("destinations", Context.MODE_PRIVATE)
        prefs = p
        _recents.value = runCatching { decode(p.getString(KEY_RECENTS, null)) }.getOrDefault(emptyList())
    }

    /** [destination] goes to the top of the recents. */
    fun add(destination: Destination) {
        // The same place again moves to the top instead of showing twice
        val list = listOf(destination) + _recents.value.filterNot { same(it, destination) }
        _recents.value = list.take(MAX_RECENTS)
        prefs?.edit()?.putString(KEY_RECENTS, encode(_recents.value))?.apply()
    }

    /** Within about 50 m: the same place, whatever it was called. */
    private fun same(a: Destination, b: Destination) =
        Math.abs(a.latitude - b.latitude) < 0.0005 && Math.abs(a.longitude - b.longitude) < 0.0005

    internal fun encode(list: List<Destination>): String = JSONArray().apply {
        list.forEach { d ->
            put(JSONObject().put("name", d.name).put("address", d.address).put("lat", d.latitude).put("lon", d.longitude))
        }
    }.toString()

    internal fun decode(json: String?): List<Destination> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Destination(o.getString("name"), o.optString("address").takeIf { it.isNotBlank() && it != "null" }, o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    private const val KEY_RECENTS = "recents"
    private const val MAX_RECENTS = 10
}
