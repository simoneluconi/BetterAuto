package com.simoneluconi.betterauto.settings

/** A section that can sit in the app bar. Settings is not one of them: it is always there. */
enum class RailItem(val section: Section) {
    HOME(Section.HOME),
    MUSIC(Section.MUSIC),
    MAP(Section.MAP),
    PHONE(Section.PHONE),
    VEHICLE(Section.VEHICLE),
    NOTIFICATIONS(Section.NOTIFICATIONS),
    /** Hidden, it still comes when the phone's apps don't all fit in the bar ([RailLayout.phoneAppsRoom]). */
    APPS(Section.APPS),
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
        // Comes by itself when needed
        RailEntry(RailItem.APPS, RailSlot.HIDDEN),
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

    /**
     * Media apps for the bar, before trimming to the room there is: pinned first, then recent ones, or
     * [suggested] while there is no history. The app the Music section follows ([followed]) is left out,
     * pinned or not: its button would only open Music again.
     */
    fun mediaApps(pinned: List<String>, recent: List<String>, followed: String?, suggested: () -> List<String>): List<String> =
        (pinned + recent.filter { it !in pinned }).ifEmpty { suggested() }.filter { it != followed }

    /** How many media and phone app buttons the bar shows, and whether its Apps button must come for the rest. */
    data class AppsRoom(val media: Int, val phone: Int, val appsButton: Boolean)

    /**
     * [free] buttons' room after the sections, for [media] media apps and [phone] phone apps. All of them if they fit;
     * otherwise the Apps button takes one place (unless [appsPlaced] in the bar already) and the media apps come first.
     */
    fun phoneAppsRoom(free: Int, media: Int, phone: Int, appsPlaced: Boolean): AppsRoom {
        val room = free.coerceAtLeast(0)
        if (media + phone <= room) return AppsRoom(media, phone, appsButton = false)
        // The button is only needed for phone apps left out: media apps live in Music too; and never without room for
        // itself, it would push Settings off the bar
        if (phone == 0 || room == 0) return AppsRoom(minOf(media, room), 0, appsButton = false)
        val left = if (appsPlaced) room else (room - 1).coerceAtLeast(0)
        val shownMedia = minOf(media, left)
        return AppsRoom(shownMedia, minOf(phone, left - shownMedia), appsButton = !appsPlaced)
    }
}

/** How the clock in the status bar is shown. */
enum class ClockStyle { HIDDEN, AUTO, H24, H12 }

/** Which microphone voice commands listen to: the car's when it has one, or always the phone's. */
enum class MicSource { CAR, PHONE }

/** How the date in the status bar is shown. */
enum class DateStyle { HIDDEN, SHORT, LONG, NUMERIC }
