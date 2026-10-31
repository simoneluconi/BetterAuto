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

    /**
     * What slot [index] shows, in order: its main widget, then those it also holds ([stacks]), a swipe away;
     * never one that another of the [visible] slots shows as its main.
     */
    fun pages(mains: List<WidgetChoice>, stacks: List<List<WidgetChoice>>, index: Int, visible: Int): List<WidgetChoice> {
        val main = mains.getOrNull(index) ?: return emptyList()
        val others = mains.take(visible).filterIndexed { i, _ -> i != index }
        return (listOf(main) + stacks.getOrNull(index).orEmpty()).distinct().filter { it == main || it !in others }
    }

    /** "A,B|C|": the widgets each slot holds besides its main one, slot by slot. */
    fun encodeStacks(stacks: List<List<WidgetChoice>>): String = stacks.joinToString("|") { stack -> stack.joinToString(",") { it.encode() } }

    /** Always [COUNT] lists; unknown names dropped. */
    fun decodeStacks(saved: String?): List<List<WidgetChoice>> {
        val parts = saved?.split("|").orEmpty()
        return List(COUNT) { i ->
            parts.getOrNull(i)?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { WidgetChoice.decode(it) }
                ?.filter { !it.type.takesParam || it.param != null }?.distinct().orEmpty()
        }
    }

    /** Slot [index] now holds [pages] besides its main one: they leave any other slot's list. */
    fun withStack(stacks: List<List<WidgetChoice>>, index: Int, pages: List<WidgetChoice>): List<List<WidgetChoice>> =
        List(COUNT) { i -> if (i == index) pages.distinct() else stacks.getOrNull(i).orEmpty().filter { it !in pages } }

    private fun fill(list: List<WidgetChoice>): List<WidgetChoice> {
        // Never a smart-home group out of nowhere: types that need a parameter only come from the driver
        val spare = (Default + WidgetType.entries.filter { !it.takesParam }.map { WidgetChoice(it) }).distinct().filter { it !in list }
        return list + spare.take(COUNT - list.size)
    }
}
