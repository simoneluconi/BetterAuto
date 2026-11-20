package com.simoneluconi.betterauto.media

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/** Media apps installed on the phone (apps exposing a MediaBrowserService), with cached label and icon. */
object MediaApps {
    data class App(val packageName: String, val label: String, val icon: ImageBitmap)

    /**
     * The apps shown lately. Bounded: the phone-apps setting lists every launcher app (often 200), and their
     * icons kept for good came to 10-20 MB in a process some phones kill for memory.
     */
    private val cache = LruCache<String, App>(CACHED_APPS)

    /** Launchable apps exposing a media browser, minus system services that are not players at all. */
    fun installed(context: Context): List<String> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in NotPlayers && pm.getLaunchIntentForPackage(it) != null }
    }

    /** Installed media apps that declare themselves as audio apps: the only ones worth suggesting unasked. */
    fun suggested(context: Context): List<String> = installed(context).filter {
        runCatching {
            context.packageManager.getApplicationInfo(it, 0).category == ApplicationInfo.CATEGORY_AUDIO
        }.getOrDefault(false)
    }

    private val NotPlayers = setOf(
        "com.android.bluetooth",
        "com.google.android.googlequicksearchbox",
        "com.google.android.projection.gearhead",
    )

    fun info(context: Context, packageName: String): App? = cache.get(packageName) ?: try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        App(
            packageName = packageName,
            label = pm.getApplicationLabel(appInfo).toString(),
            icon = pm.getApplicationIcon(appInfo).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
        ).also { cache.put(packageName, it) }
    } catch (_: Exception) {
        null
    }

    private const val ICON_PX = 128
    private const val CACHED_APPS = 64
}
