package com.simoneluconi.betterauto.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
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

/** Folders are fetched in one page; apps that page by themselves slice at this size. */
private const val PAGE_SIZE = 2000

/**
 * Browses a music app's library through its media browser service (Media3 or the older kind), like
 * Android Auto does. Many apps only accept the callers they trust, so [LibraryState.Unavailable] is a
 * normal outcome. Create, use and close on the main thread.
 */
@OptIn(UnstableApi::class)
class MediaLibrary(private val context: Context, private val packageName: String) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Connecting)
    val state: StateFlow<LibraryState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private var closed = false

    /** Opened folders, root first: id to title. */
    private val path = ArrayList<Pair<String, String?>>()

    /** Only the latest folder request may publish: going back quickly must not show a stale folder. */
    private var request = 0

    fun connect() {
        val service = context.packageManager
            .queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(packageName), 0)
            .firstOrNull()?.serviceInfo
        if (service == null) {
            _state.value = LibraryState.Unavailable
            return
        }
        val token = SessionToken(context, ComponentName(service.packageName, service.name))
        val future = MediaBrowser.Builder(context, token).buildAsync()
        browserFuture = future
        future.addListener({
            val b = runCatching { future.get() }.getOrNull()
            if (b == null || closed) {
                b?.release()
                if (!closed) _state.value = LibraryState.Unavailable
                return@addListener
            }
            browser = b
            val rootFuture = b.getLibraryRoot(null)
            rootFuture.addListener({
                val root = runCatching { rootFuture.get() }.getOrNull()?.value?.mediaId
                if (root == null) _state.value = LibraryState.Unavailable else open(root, null)
            }, context.mainExecutor)
        }, context.mainExecutor)
    }

    fun select(item: LibraryItem) {
        val b = browser ?: return
        when {
            item.browsable -> open(item.id, item.title)
            item.playable -> {
                b.setMediaItem(MediaItem.Builder().setMediaId(item.id).build())
                b.play()
            }
        }
    }

    /** One folder up; false at the root. */
    fun back(): Boolean {
        if (path.size <= 1) return false
        path.removeAt(path.lastIndex)
        val (id, title) = path.removeAt(path.lastIndex)
        open(id, title)
        return true
    }

    fun close() {
        closed = true
        scope.cancel()
        browser?.release()
        browser = null
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browserFuture = null
    }

    private fun open(id: String, title: String?) {
        val b = browser ?: return
        path.add(id to title)
        _state.value = LibraryState.Connecting
        val current = ++request
        val future = b.getChildren(id, 0, PAGE_SIZE, null)
        future.addListener({
            if (current != request || closed) return@addListener
            val result = runCatching { future.get() }.getOrNull()
            if (result == null || result.resultCode != LibraryResult.RESULT_SUCCESS) {
                _state.value = LibraryState.Unavailable
                return@addListener
            }
            val children = result.value.orEmpty()
            scope.launch {
                // Art bytes are decoded and glyphs detected off the main thread: folders can hold hundreds of items
                val items = withContext(Dispatchers.Default) {
                    children.map { item ->
                        val m = item.mediaMetadata
                        val icon = m.artworkData?.let { Artwork.decode(it, ICON_PX) }
                        LibraryItem(
                            id = item.mediaId,
                            title = m.title?.toString().orEmpty(),
                            subtitle = (m.subtitle ?: m.artist)?.toString().orEmpty(),
                            icon = icon?.asImageBitmap(),
                            glyph = icon?.let(::isGlyph) ?: false,
                            browsable = m.isBrowsable == true,
                            playable = m.isPlayable == true,
                        )
                    }
                }
                if (current != request) return@launch
                _state.value = LibraryState.Loaded(title, items, path.size > 1)
                loadIcons(current, children)
            }
        }, context.mainExecutor)
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
    private fun loadIcons(current: Int, children: List<MediaItem>) {
        val missing = children.filter { it.mediaMetadata.artworkData == null && it.mediaMetadata.artworkUri != null }
        if (missing.isEmpty()) return
        scope.launch {
            val icons = withContext(Dispatchers.IO) {
                missing.associate { item ->
                    item.mediaId to runCatching {
                        Artwork.decode(context, item.mediaMetadata.artworkUri!!, ICON_PX)?.let { it to isGlyph(it) }
                    }.getOrNull()
                }
            }
            val loaded = _state.value as? LibraryState.Loaded ?: return@launch
            if (current != request) return@launch
            _state.value = loaded.copy(
                items = loaded.items.map { item ->
                    val icon = icons[item.id]
                    if (item.icon != null || icon == null) item
                    else item.copy(icon = icon.first.asImageBitmap(), glyph = icon.second)
                }
            )
        }
    }
}
