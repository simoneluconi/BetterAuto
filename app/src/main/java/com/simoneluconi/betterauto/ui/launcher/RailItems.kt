package com.simoneluconi.betterauto.ui.launcher

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.settings.RailItem
import com.simoneluconi.betterauto.settings.RailSlot
import com.simoneluconi.betterauto.ui.CarIcons

val RailItem.icon: ImageVector
    get() = when (this) {
        RailItem.HOME -> CarIcons.Home
        RailItem.MUSIC -> CarIcons.Music
        RailItem.MAP -> CarIcons.Map
        RailItem.PHONE -> CarIcons.Phone
        RailItem.VEHICLE -> CarIcons.Car
        RailItem.NOTIFICATIONS -> CarIcons.Bell
    }

@get:StringRes
val RailItem.label: Int
    get() = when (this) {
        RailItem.HOME -> R.string.nav_home
        RailItem.MUSIC -> R.string.nav_music
        RailItem.MAP -> R.string.nav_map
        RailItem.PHONE -> R.string.nav_phone
        RailItem.VEHICLE -> R.string.nav_vehicle
        RailItem.NOTIFICATIONS -> R.string.nav_notifications
    }

@get:StringRes
val RailSlot.label: Int
    get() = when (this) {
        RailSlot.BAR -> R.string.rail_slot_bar
        RailSlot.GROUP_1 -> R.string.rail_slot_group_1
        RailSlot.GROUP_2 -> R.string.rail_slot_group_2
        RailSlot.HIDDEN -> R.string.rail_slot_hidden
    }
