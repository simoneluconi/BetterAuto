package com.simoneluconi.betterauto.smarthome

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The driver's choices per provider ([ProviderChoice]), persisted; every change saves at once. */
class SmartHomeSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("smarthome", Context.MODE_PRIVATE)

    private val _choices = MutableStateFlow(load())
    val choices: StateFlow<Map<String, ProviderChoice>> = _choices

    private val _arrivalEnabled = MutableStateFlow(prefs.getBoolean(KEY_ARRIVAL, true))
    /** The arrival buttons at all, over every provider and place: off keeps each place's own choices. */
    val arrivalEnabled: StateFlow<Boolean> = _arrivalEnabled

    fun setArrivalEnabled(value: Boolean) {
        _arrivalEnabled.value = value
        prefs.edit { putBoolean(KEY_ARRIVAL, value) }
    }

    fun choice(providerId: String): ProviderChoice = _choices.value[providerId] ?: ProviderChoice()

    @Synchronized
    fun update(providerId: String, change: (ProviderChoice) -> ProviderChoice) {
        val updated = change(choice(providerId))
        _choices.value = _choices.value + (providerId to updated)
        prefs.edit { putString(KEY_PREFIX + providerId, encode(updated)) }
    }

    @Synchronized
    fun forget(providerId: String) {
        _choices.value = _choices.value - providerId
        prefs.edit { remove(KEY_PREFIX + providerId) }
    }

    private fun load(): Map<String, ProviderChoice> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
        decode(value)?.let { key.removePrefix(KEY_PREFIX) to it }
    }.toMap()

    companion object {
        private const val KEY_PREFIX = "choice."
        private const val KEY_ARRIVAL = "arrival_enabled"

        fun encode(c: ProviderChoice): String = JSONObject()
            .putOpt("selected", c.selected?.let { JSONArray(it.sorted()) })
            .put("places", JSONObject().apply {
                for ((id, p) in c.places.toSortedMap()) {
                    put(id, JSONObject().put("devices", JSONArray(p.devices.sorted())).put("offset", p.offsetM).put("on", p.on))
                }
            })
            .toString()

        /** Also reads the earlier single-place form ("arrival", "arrivalConfirmed", "offset", "place"). */
        fun decode(text: String): ProviderChoice? = runCatching {
            val json = JSONObject(text)
            val places = json.optJSONObject("places")?.let { o ->
                o.keys().asSequence().mapNotNull { id ->
                    val p = o.optJSONObject(id) ?: return@mapNotNull null
                    id to PlaceArrival(
                        devices = p.optJSONArray("devices").strings().toSet(),
                        offsetM = p.optInt("offset", ProviderChoice.DEFAULT_OFFSET_M),
                        on = p.optBoolean("on"),
                    )
                }.toMap()
            } ?: json.optJSONArray("arrival")?.strings()?.toSet()?.let { devices ->
                mapOf(
                    (json.optStringOrNull("place") ?: ProviderChoice.HOME_PLACE) to PlaceArrival(
                        devices = devices,
                        offsetM = json.optInt("offset", ProviderChoice.DEFAULT_OFFSET_M),
                        on = json.optBoolean("arrivalConfirmed"),
                    )
                )
            }.orEmpty()
            ProviderChoice(selected = json.optJSONArray("selected")?.strings()?.toSet(), places = places)
        }.getOrNull()
    }
}
