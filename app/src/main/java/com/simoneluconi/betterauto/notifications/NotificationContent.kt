package com.simoneluconi.betterauto.notifications

import com.simoneluconi.betterauto.car.VehicleData
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.NotificationPrivacy

/** Whether notifications may show what they say, from the privacy setting and who is aboard. */
object NotificationContent {
    /**
     * Auto when no source in this session says who is aboard: what the setting did by default before
     * Auto existed (contents shown).
     */
    const val HIDDEN_WHEN_UNKNOWN = false

    /** [othersOnBoard]: anyone besides the driver (passenger, rear seats), null when the car doesn't say. */
    fun hidden(mode: NotificationPrivacy, othersOnBoard: Boolean?): Boolean = when (mode) {
        NotificationPrivacy.ON -> true
        NotificationPrivacy.OFF -> false
        NotificationPrivacy.AUTO -> othersOnBoard ?: HIDDEN_WHEN_UNKNOWN
    }

    fun hiddenNow(settings: LauncherSettings): Boolean = hidden(settings.notificationPrivacy.value, VehicleData.othersOnBoard.value)
}
