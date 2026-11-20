package com.simoneluconi.betterauto.projection

import android.content.Context
import android.content.Intent

/** The phone apps a driver can pick to open in the car ([PhoneAppDisplay]). */
object PhoneAppChoices {
    /**
     * Chosen until the driver changes the list, where installed: the apps most wanted in a car (maps, video and the
     * web while parked, chats). No music players: those have the Music section, and as phone apps they would leave it.
     */
    val DEFAULTS = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.google.android.youtube",
        "com.android.chrome",
        "com.whatsapp",
        "org.telegram.messenger",
    )

    /** Every app with an icon in the phone's launcher, but ours, by name. */
    fun installed(context: Context): List<String> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { it to runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }
            .sortedBy { it.second.lowercase() }
            .map { it.first }
            .toList()
    }

    /** Of the [chosen] apps, those still installed with a launcher activity, in their order. */
    fun available(context: Context, chosen: List<String>): List<String> =
        chosen.filter { context.packageManager.getLaunchIntentForPackage(it) != null }
}
