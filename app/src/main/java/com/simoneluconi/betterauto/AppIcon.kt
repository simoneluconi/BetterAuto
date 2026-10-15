package com.simoneluconi.betterauto

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream

/** The launcher icon rendered as PNG, for the head unit's list of sources. */
object AppIcon {
    /** The three sizes the service discovery request carries: small, medium, large. */
    val Sizes = listOf(64, 128, 256)

    fun png(context: Context, sizePx: Int): ByteArray? = runCatching {
        val drawable = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, context.theme) ?: return null
        val bitmap = drawable.toBitmap(sizePx, sizePx)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()
}
