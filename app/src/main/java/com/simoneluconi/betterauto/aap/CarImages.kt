package com.simoneluconi.betterauto.aap

import android.graphics.Bitmap
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream

/** A picture for the car (cover, caller photo): at most [maxPx] on its longer side, as JPEG. */
internal fun Bitmap.toCarJpeg(maxPx: Int, quality: Int): ByteString {
    val scale = minOf(1f, maxPx.toFloat() / maxOf(width, height))
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true) else this
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return ByteString.copyFrom(out.toByteArray())
}
