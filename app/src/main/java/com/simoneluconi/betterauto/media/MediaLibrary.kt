@file:Suppress("DEPRECATION") // androidx.media compat APIs; to be migrated to Media3

package com.simoneluconi.betterauto.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageBitmap?,
    val browsable: Boolean,
    val playable: Boolean,
    /** Single-color glyph on transparency: tint it with the theme instead of showing it as a picture. */
    val glyph: Boolean = false,
)

sealed interface LibraryState {
    data object Connecting : LibraryState

    /** The app has no media browser, or refuses apps other than the ones it trusts. */
    data object Unavailable : LibraryState

    data class Loaded(val title: String?, val items: List<LibraryItem>, val canGoBack: Boolean) : LibraryState
}

/** Icons are shown at about 60 design units: decoding bigger ones only costs memory. */
private const val ICON_PX = 128

/**
 * Browses a music app's library through its MediaBrowserService, like Android Auto does.
 * Many apps only accept the callers they trust, so [LibraryState.Unavailable] is a normal outcome.
 * Create, use and close on the main thread.
 */
class MediaLibrary(private val context: Context, private val packageName: String) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Connecting)
    val state: StateFlow<LibraryState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var browser: MediaBrowserCompat? = null

    /** Opened folders, root first: id to title. */
    private val path = ArrayList<Pair<String, String?>>()

    private val subscription = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
            if (path.lastOrNull()?.first != parentId) return
            scope.launch {
                // Glyph detection reads pixels: not on the main thread for a folder of hundreds of items
                val items = withContext(Dispatchers.Default) {
                    children.map { item ->
                        val d = item.description
                        LibraryItem(
                            id = item.mediaId.orEmpty(),
                            title = d.title?.toString().orEmpty(),
                            subtitle = d.subtitle?.toString().orEmpty(),
                            icon = d.iconBitmap?.asImageBitmap(),
                            glyph = d.iconBitmap?.let(::isGlyph) ?: false,
                            browsable = item.isBrowsable,
                            playable = item.isPlayable,
                        )
                    }
                }
                if (path.lastOrNull()?.first != parentId) return@launch
                _state.value = LibraryState.Loaded(path.last().second, items, path.size > 1)
                loadIcons(parentId, children)
            }
        }

        override fun onError(parentId: String) {
            _state.value = LibraryState.Unavailable
        }
    }

    fun connect() {
        val service = context.packageManager
            .queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(packageName), 0)
            .firstOrNull()?.serviceInfo
        if (service == null) {
            _state.value = LibraryState.Unavailable
            return
        }
        browser = MediaBrowserCompat(
            context,
            ComponentName(service.packageName, service.name),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val root = browser?.root ?: return
                    open(root, null)
                }

                override fun onConnectionFailed() {
                    _state.value = LibraryState.Unavailable
                }

                override fun onConnectionSuspended() {
                    _state.value = LibraryState.Unavailable
                }
            },
            null,
        ).also { it.connect() }
    }

    fun select(item: LibraryItem) {
        val b = browser ?: return
        when {
            item.browsable -> open(item.id, item.title)
            item.playable -> MediaControllerCompat(context, b.sessionToken).transportControls.playFromMediaId(item.id, null)
        }
    }

    /** One folder up; false at the root. */
    fun back(): Boolean {
        if (path.size <= 1) return false
        browser?.unsubscribe(path.removeAt(path.lastIndex).first)
        val (id, title) = path.removeAt(path.lastIndex)
        open(id, title)
        return true
    }

    fun close() {
        scope.cancel()
        browser?.disconnect()
        browser = null
    }

    private fun open(id: String, title: String?) {
        path.lastOrNull()?.let { browser?.unsubscribe(it.first) }
        path.add(id to title)
        _state.value = LibraryState.Connecting
        browser?.subscribe(id, subscription)
    }

    /** A glyph has plenty of transparent pixels and all opaque ones of about the same color. */
    private fun isGlyph(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var transparent = 0
        var reference = -1
        var sameColor = true
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 40) {
                transparent++
                continue
            }
            if (reference == -1) reference = c
            val distance = abs(Color.red(c) - Color.red(reference)) +
                abs(Color.green(c) - Color.green(reference)) + abs(Color.blue(c) - Color.blue(reference))
            if (distance > 90) sameColor = false
        }
        return transparent > 24 * 24 / 4 && sameColor
    }

    /** Items often carry only an icon URI: decode those off the main thread, at list size, and republish. */
    private fun loadIcons(parentId: String, children: List<MediaBrowserCompat.MediaItem>) {
        val missing = children.filter { it.description.iconBitmap == null && it.description.iconUri != null }
        if (missing.isEmpty()) return
        scope.launch {
            val icons = withContext(Dispatchers.IO) {
                missing.associate { item ->
                    item.mediaId.orEmpty() to runCatching {
                        val source = ImageDecoder.createSource(context.contentResolver, item.description.iconUri!!)
                        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            decoder.setTargetSampleSize((minOf(info.size.width, info.size.height) / ICON_PX).coerceAtLeast(1))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        bitmap to isGlyph(bitmap)
                    }.getOrNull()
                }
            }
            val current = _state.value as? LibraryState.Loaded ?: return@launch
            if (path.lastOrNull()?.first != parentId) return@launch
            _state.value = current.copy(
                items = current.items.map { item ->
                    val loaded = icons[item.id]
                    if (item.icon != null || loaded == null) item
                    else item.copy(icon = loaded.first.asImageBitmap(), glyph = loaded.second)
                }
            )
        }
    }
}
