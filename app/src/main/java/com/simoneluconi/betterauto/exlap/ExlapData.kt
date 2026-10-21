package com.simoneluconi.betterauto.exlap

import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.car.VehicleSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One member of an Exlap object (e.g. engineSpeed / engineSpeed) with its latest value, null while the
 * car has none. Members of nested objects and lists are flattened: "seats[0].occupied". [type] is the
 * member's kind from the object's Interface (Absolute, Relative, Activity...).
 */
data class ExlapValue(
    val url: String,
    val name: String,
    val value: String?,
    val unit: String?,
    val description: String?,
    val type: String? = null,
)

/**
 * The values a VW car's Exlap server pushes, as they arrive. Fast objects change up to 10 times a
 * second: the first change after a quiet moment is published at once, the rest at most every
 * [publishIntervalMs]. Each publish also feeds [ExlapVehicleSource].
 */
object ExlapData {
    private val _values = MutableStateFlow<Map<String, ExlapValue>>(emptyMap())
    /** By "url/name", in the order the car listed them. */
    val values: StateFlow<Map<String, ExlapValue>> = _values

    private val pending = LinkedHashMap<String, ExlapValue>()
    private var lastPublish = 0L
    private var dirty = false
    private var scheduled = false

    /** Tests set 0 to see every change at once. */
    @Volatile
    internal var publishIntervalMs = 250L

    private val timer by lazy { Executors.newSingleThreadScheduledExecutor { Thread(it, "exlap-data").apply { isDaemon = true } } }

    @Synchronized
    fun setSchema(members: List<ExlapValue>) {
        pending.clear()
        members.forEach { pending[key(it.url, it.name)] = it }
        publish()
    }

    @Synchronized
    fun update(url: String, name: String, value: String?) {
        val key = key(url, name)
        val known = pending[key] ?: ExlapValue(url, name, null, null, null)
        if (known.value == value && pending.containsKey(key)) return
        pending[key] = known.copy(value = value)
        dirty = true
    }

    /** The end of a batch of updates: published now, or as soon as the interval allows. */
    @Synchronized
    fun flush() {
        if (!dirty) return
        val wait = lastPublish + publishIntervalMs - System.currentTimeMillis()
        if (wait <= 0) return publish()
        if (scheduled) return
        scheduled = true
        timer.schedule({
            synchronized(this) {
                scheduled = false
                if (dirty) publish()
            }
        }, wait, TimeUnit.MILLISECONDS)
    }

    /** The latest values, including those not published yet. */
    @Synchronized
    fun current(): Map<String, ExlapValue> = LinkedHashMap(pending)

    @Synchronized
    fun clear() {
        pending.clear()
        dirty = false
        _values.value = emptyMap()
        ExlapVehicleSource.stop()
    }

    private fun publish() {
        lastPublish = System.currentTimeMillis()
        dirty = false
        val snapshot = LinkedHashMap(pending)
        _values.value = snapshot
        ExlapVehicleSource.onValues(snapshot)
    }

    private fun key(url: String, name: String) = "$url/$name"
}

/** Exlap as a [VehicleSource]: canonical fields from [ExlapMapping], everything else as raw rows. */
object ExlapVehicleSource {
    val SOURCE = VehicleSource("exlap", R.string.vehicle_exlap, priority = 10)

    fun onValues(values: Map<String, ExlapValue>) {
        val mapped = ExlapMapping.map(values.values)
        val rest = values.filterKeys { it !in mapped.used }.values
        VehicleData.replace(SOURCE, mapped.fields, exlapRows(rest))
    }

    /** The Exlap session ended: its values stay as last known. */
    fun stop() = VehicleData.stop(SOURCE)
}
