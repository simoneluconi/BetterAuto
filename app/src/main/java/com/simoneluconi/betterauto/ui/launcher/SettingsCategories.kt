package com.simoneluconi.betterauto.ui.launcher

import androidx.compose.ui.graphics.vector.ImageVector
import com.simoneluconi.betterauto.settings.SettingsCategory
import com.simoneluconi.betterauto.ui.CarIcons

/** The category's icon, the same in the car's settings list and on the phone's settings home. */
val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.HOME_SCREEN -> CarIcons.Home
        SettingsCategory.DISPLAY -> CarIcons.Sun
        SettingsCategory.MUSIC -> CarIcons.Music
        SettingsCategory.NOTIFICATIONS -> CarIcons.Bell
        SettingsCategory.VOICE -> CarIcons.Mic
        SettingsCategory.VEHICLE -> CarIcons.Car
        SettingsCategory.SMART_HOME -> CarIcons.SmartHome
        SettingsCategory.ADVANCED -> CarIcons.Settings
    }
