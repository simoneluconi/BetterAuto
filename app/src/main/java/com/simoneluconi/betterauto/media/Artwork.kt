package com.simoneluconi.betterauto.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri

/** Media3 hands over artwork as encoded bytes or as a URI: decoding, bounded to the size the screen needs. */
internal object Artwork {
    private val LocalSchemes = setOf("content", "file", "android.resource")

    /** Decodes [data] with the longest side at most about [maxPx]; null when it is not an image. */
    fun decode(data: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Decodes a local [uri] (content, file, resource) at about [maxPx]; web URIs are not fetched. */
    fun decode(context: Context, uri: Uri, maxPx: Int): Bitmap? {
        if (uri.scheme !in LocalSchemes) return null
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / maxPx).coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** Whichever the player provided, bytes first. */
    fun decode(context: Context, data: ByteArray?, uri: Uri?, maxPx: Int): Bitmap? = runCatching {
        data?.let { decode(it, maxPx) } ?: uri?.let { decode(context, it, maxPx) }
    }.getOrNull()
}
