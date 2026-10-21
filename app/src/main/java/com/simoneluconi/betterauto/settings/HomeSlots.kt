package com.simoneluconi.betterauto.settings

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a home slot shows: a widget type and, for types that need one, a parameter (e.g. which smart-home
 * group). Types without a parameter leave it null.
 */
data class WidgetChoice(val type: WidgetType, val param: String? = null) {
    /** "TYPE" or "TYPE:param", the parameter URL-encoded so it never holds the list's commas. */
    fun encode(): String = if (param == null) type.name else "${type.name}:${URLEncoder.encode(param, "UTF-8")}"

    companion object {
        fun decode(text: String): WidgetChoice? {
            val name = text.substringBefore(':').trim()
            val type = WidgetType.entries.firstOrNull { it.name == name } ?: return null
            val param = text.substringAfter(':', "").takeIf { ':' in text }?.let { URLDecoder.decode(it, "UTF-8") }
            return WidgetChoice(type, param)
        }
    }
}

/**
 * The home screen's slots, always [COUNT] of them whatever the layout: the layout shows the first one, two
 * or three, so switching layouts never loses an arrangement. A widget sits in one slot at most.
 */
object HomeSlots {
    const val COUNT = 3

    /** Nothing that needs setting up first, no placeholder. */
    // Works out of the box, nothing to set up on the phone: music, the time (with the outside temperature), notifications
    val Default = listOf(WidgetChoice(WidgetType.MUSIC), WidgetChoice(WidgetType.CLOCK), WidgetChoice(WidgetType.NOTIFICATIONS))

    fun encode(slots: List<WidgetChoice>): String = slots.joinToString(",") { it.encode() }

    /**
     * The saved slots, or [Default] with nothing saved. Unknown names are dropped and the gaps filled,
     * so there are always [COUNT] different widgets.
     */
    fun decode(saved: String?): List<WidgetChoice> {
        val list = saved?.split(",")?.mapNotNull { WidgetChoice.decode(it) }?.filter { !it.type.takesParam || it.param != null }?.distinct() ?: Default
        return fill(list.take(COUNT))
    }

    /** [choice] goes to slot [index]; if it already sits in another slot, the two swap places. */
    fun pick(slots: List<WidgetChoice>, index: Int, choice: WidgetChoice): List<WidgetChoice> {
        if (index !in slots.indices) return slots
        val result = slots.toMutableList()
        val previous = result.indexOf(choice)
        if (previous >= 0) result[previous] = result[index]
        result[index] = choice
        return result
    }

    /** Every widget moves up one slot and the first goes last, so each can take the big slot in turn. */
    fun rotate(slots: List<WidgetChoice>): List<WidgetChoice> =
        if (slots.size < 2) slots else slots.drop(1) + slots.first()

    private fun fill(list: List<WidgetChoice>): List<WidgetChoice> {
        // Never a smart-home group out of nowhere: types that need a parameter only come from the driver
        val spare = (Default + WidgetType.entries.filter { !it.takesParam }.map { WidgetChoice(it) }).distinct().filter { it !in list }
        return list + spare.take(COUNT - list.size)
    }
}
