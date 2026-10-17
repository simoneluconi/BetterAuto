package com.simoneluconi.betterauto.exlap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One member of an Exlap object (e.g. engineSpeed / val) with its latest value, null while the car has none. */
data class ExlapValue(val url: String, val name: String, val value: String?, val unit: String?, val description: String?)

/**
 * The values a VW car's Exlap server pushes, as they arrive. Updates come up to 10 times a second per
 * object: they are collected and published at most twice a second.
 */
object ExlapData {
    private val _values = MutableStateFlow<Map<String, ExlapValue>>(emptyMap())
    /** By "url/name", in the order the car listed them. */
    val values: StateFlow<Map<String, ExlapValue>> = _values

    private val pending = LinkedHashMap<String, ExlapValue>()
    private var lastPublish = 0L

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
        if (known.value == value) return
        pending[key] = known.copy(value = value)
        val now = System.nanoTime()
        if (now - lastPublish >= PUBLISH_INTERVAL_NS) publish()
    }

    /** Whatever arrived since the last publish, at the end of a batch. */
    @Synchronized
    fun flush() = publish()

    @Synchronized
    fun clear() {
        pending.clear()
        _values.value = emptyMap()
    }

    private fun publish() {
        lastPublish = System.nanoTime()
        _values.value = LinkedHashMap(pending)
    }

    private fun key(url: String, name: String) = "$url/$name"

    private const val PUBLISH_INTERVAL_NS = 500_000_000L
}
