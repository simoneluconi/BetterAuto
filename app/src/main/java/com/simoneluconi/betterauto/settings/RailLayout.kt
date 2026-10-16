package com.simoneluconi.betterauto.settings

/** A section that can sit in the app bar. Settings is not one of them: it is always there. */
enum class RailItem(val section: Section) {
    HOME(Section.HOME),
    MUSIC(Section.MUSIC),
    MAP(Section.MAP),
    PHONE(Section.PHONE),
    VEHICLE(Section.VEHICLE),
    NOTIFICATIONS(Section.NOTIFICATIONS),
}

/** Where an item goes: its own button, one of two grouped buttons, or nowhere. */
enum class RailSlot { BAR, GROUP_1, GROUP_2, HIDDEN }

data class RailEntry(val item: RailItem, val slot: RailSlot)

/** The app bar as the driver arranged it: order and place of every item. */
object RailLayout {
    val Default = listOf(
        RailEntry(RailItem.HOME, RailSlot.BAR),
        RailEntry(RailItem.MUSIC, RailSlot.BAR),
        RailEntry(RailItem.MAP, RailSlot.BAR),
        RailEntry(RailItem.PHONE, RailSlot.BAR),
        RailEntry(RailItem.VEHICLE, RailSlot.BAR),
        // The bell in the status bar already opens them
        RailEntry(RailItem.NOTIFICATIONS, RailSlot.HIDDEN),
    )

    val Groups = listOf(RailSlot.GROUP_1, RailSlot.GROUP_2)

    fun encode(entries: List<RailEntry>) = entries.joinToString(",") { "${it.item.name}:${it.slot.name}" }

    /** Saved layout, with items added in later versions appended in their default place. */
    fun decode(saved: String?): List<RailEntry> {
        val parsed = saved?.split(",")?.mapNotNull { part ->
            val (item, slot) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            val railItem = RailItem.entries.firstOrNull { it.name == item } ?: return@mapNotNull null
            RailEntry(railItem, RailSlot.entries.firstOrNull { it.name == slot } ?: RailSlot.BAR)
        }.orEmpty().distinctBy { it.item }
        return parsed + Default.filter { d -> parsed.none { it.item == d.item } }
    }

    fun move(entries: List<RailEntry>, item: RailItem, delta: Int): List<RailEntry> {
        val from = entries.indexOfFirst { it.item == item }
        val to = (from + delta).coerceIn(0, entries.lastIndex)
        if (from < 0 || from == to) return entries
        return entries.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun place(entries: List<RailEntry>, item: RailItem, slot: RailSlot) =
        entries.map { if (it.item == item) it.copy(slot = slot) else it }
}

/** How the clock in the status bar is shown. */
enum class ClockStyle { HIDDEN, AUTO, H24, H12 }

/** How the date in the status bar is shown. */
enum class DateStyle { HIDDEN, SHORT, LONG, NUMERIC }
