@file:Suppress("DEPRECATION") // androidx.media compat APIs; to be migrated to Media3

package com.simoneluconi.betterauto.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.simoneluconi.betterauto.settings.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Track(
    val packageName: String,
    val appLabel: String,
    val appIcon: ImageBitmap?,
    val title: String,
    val artist: String,
    val album: String,
    val art: ImageBitmap?,
    /** Main color of the album art, null until computed or when there is no art. */
    val accent: Color?,
    val durationMs: Long,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() of [positionMs]. */
    val positionUpdatedAt: Long,
    val speed: Float,
    val playing: Boolean,
    val shuffle: Boolean,
    val repeat: Boolean,
    /** Repeating the current track only (a kind of [repeat]). */
    val repeatOne: Boolean,
    /** Name of what is playing as a whole (playlist, album, station), when the player tells it. */
    val playlist: String?,
    val upNext: String?,
    /** The player's queue, if the app shares it (many do, some don't). */
    val queue: List<QueueEntry>,
    val activeQueueId: Long,
    /** App-specific buttons (favorite, shuffle, ...) published by the player. */
    val customActions: List<MediaAction>,
) {
    fun positionAt(now: Long): Long {
        val elapsed = if (playing) ((now - positionUpdatedAt) * speed).toLong() else 0L
        val position = positionMs + elapsed
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

data class QueueEntry(val id: Long, val title: String, val subtitle: String, val icon: ImageBitmap?)

data class MediaAction(val action: String, val name: String, val icon: ImageBitmap?)

/**
 * Follows the phone's active media session (any app: Spotify, YouTube Music, ...) and controls it.
 * Needs notification access for [MediaListenerService]; [hasAccess] reports whether we have it.
 * Main thread only, except [pause].
 */
object NowPlaying {
    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track

    private val _hasAccess = MutableStateFlow(true)
    val hasAccess: StateFlow<Boolean> = _hasAccess

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appContext: Context
    private var users = 0
    private var sessionManager: MediaSessionManager? = null

    // Read by pause() from the session threads; written on the main thread only
    @Volatile
    private var controller: MediaControllerCompat? = null
    // Each metadata read hands out a new Bitmap copy, so the art is identified by track + size, not by instance
    private var accentKey: String? = null
    private val actionIcons = HashMap<String, ImageBitmap?>()
    private const val MAX_QUEUE = 200
    private const val ACTION_ICON_PX = 96
    private const val QUEUE_ART_PX = 128
    private const val ART_BEHIND = 5
    private const val ART_AHEAD = 40
    private const val TAG = "BetterAuto"
    private val queueArtCache = LruCache<String, ImageBitmap>(80)
    private val queueArtLoading = HashSet<String>()
    private val queueArtFailed = HashMap<String, Long>()
    private const val ART_RETRY_MS = 15_000L
    @OptIn(ExperimentalCoroutinesApi::class)
    private val artDispatcher = Dispatchers.IO.limitedParallelism(3)
    private var accent: Color? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { onSessionsChanged(it) }

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = publish()
        override fun onShuffleModeChanged(shuffleMode: Int) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()
        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    /** Call from the main thread; every acquire needs a matching [release]. */
    fun acquire(context: Context) {
        appContext = context.applicationContext
        users++
        if (users == 1 || !_hasAccess.value) start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        attach(null)
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    /** The one control safe from any thread: a disconnect must pause before the phone volume comes back. */
    fun pause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) c.transportControls.pause()
    }

    /** Resumes the current player, or starts the most recently used music app if none is active. */
    fun resume() {
        val c = controller
        if (c != null) {
            if (c.playbackState?.state != PlaybackStateCompat.STATE_PLAYING) c.transportControls.play()
            return
        }
        LauncherSettings.get(appContext).recentApps.value.firstOrNull()?.let { open(it) }
    }

    fun previous() = controller?.transportControls?.skipToPrevious()

    /** Standard shuffle when the player supports it, otherwise the player's own "shuffle" button. */
    fun toggleShuffle() {
        val c = controller ?: return
        if (supports(c, PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE)) {
            val on = c.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
            c.transportControls.setShuffleMode(
                if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL
            )
        } else {
            customActionNamed(c, "shuffle")?.let { c.transportControls.sendCustomAction(it, null) }
        }
    }

    /** Standard repeat when the player supports it, otherwise the player's own "repeat" button. */
    fun toggleRepeat() {
        val c = controller ?: return
        if (supports(c, PlaybackStateCompat.ACTION_SET_REPEAT_MODE)) {
            val on = c.repeatMode != PlaybackStateCompat.REPEAT_MODE_NONE
            c.transportControls.setRepeatMode(
                if (on) PlaybackStateCompat.REPEAT_MODE_NONE else PlaybackStateCompat.REPEAT_MODE_ALL
            )
        } else {
            customActionNamed(c, "repeat")?.let { c.transportControls.sendCustomAction(it, null) }
        }
    }

    fun skipToQueueItem(id: Long) = controller?.transportControls?.skipToQueueItem(id)

    fun sendCustomAction(action: String) = controller?.transportControls?.sendCustomAction(action, null)

    private fun supports(c: MediaControllerCompat, action: Long) =
        (c.playbackState?.actions ?: 0L) and action != 0L

    private fun customActionNamed(c: MediaControllerCompat, word: String) =
        c.playbackState?.customActions?.firstOrNull { it.name.toString().contains(word, ignoreCase = true) }?.action

    /** Switches to a media app: controls its session if active, otherwise asks it to start playing. */
    fun open(packageName: String) {
        LauncherSettings.get(appContext).markRecent(packageName)
        val active = sessionManager?.let { activeSessions(it) }?.firstOrNull { it.packageName == packageName }
        if (active != null) {
            attach(active)
            return
        }
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            appContext.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(packageName)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            )
        }
    }

    private fun start() {
        val msm = appContext.getSystemService(MediaSessionManager::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), main)
            sessionManager = msm
            _hasAccess.value = true
            onSessionsChanged(activeSessions(msm))
        } catch (_: SecurityException) {
            _hasAccess.value = false
        }
    }

    private fun refresh() {
        val msm = sessionManager ?: return
        onSessionsChanged(activeSessions(msm))
    }

    private fun activeSessions(msm: MediaSessionManager): List<MediaController> =
        try {
            msm.getActiveSessions(listenerComponent())
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun listenerComponent() = ComponentName(appContext, MediaListenerService::class.java)

    private fun onSessionsChanged(sessions: List<MediaController>?) {
        val list = sessions.orEmpty()
        val best = list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken?.token) {
            publish()
            return
        }
        attach(best)
    }

    private fun attach(session: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = session?.let {
            MediaControllerCompat(appContext, MediaSessionCompat.Token.fromToken(it.sessionToken))
        }
        controller?.registerCallback(callback, main)
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _track.value = null
            return
        }
        val metadata = c.metadata
        val state = c.playbackState
        val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
        val art = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        updateAccent(art, metadata?.let { "${it.text(MediaMetadata.METADATA_KEY_ALBUM)}|${it.text(MediaMetadata.METADATA_KEY_TITLE)}" })
        val app = MediaApps.info(appContext, c.packageName)
        if (playing) LauncherSettings.get(appContext).markRecent(c.packageName)

        _track.value = Track(
            packageName = c.packageName,
            appLabel = app?.label ?: c.packageName,
            appIcon = app?.icon,
            title = metadata?.text(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
            artist = metadata?.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            album = metadata?.text(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = art?.asImageBitmap(),
            accent = if (art != null) accent else null,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            positionMs = state?.position ?: 0,
            positionUpdatedAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            playing = playing,
            shuffle = c.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL,
            repeat = c.repeatMode != PlaybackStateCompat.REPEAT_MODE_NONE && c.repeatMode >= 0,
            repeatOne = c.repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE,
            playlist = c.queueTitle?.toString()?.takeIf { it.isNotBlank() },
            upNext = nextInQueue(c),
            queue = queueEntries(c, state?.activeQueueItemId ?: -1L),
            activeQueueId = state?.activeQueueItemId ?: -1L,
            customActions = state?.customActions.orEmpty().map { action ->
                MediaAction(action.action, action.name.toString(), actionIcon(c.packageName, action.icon))
            },
        )
    }

    private fun queueEntries(c: MediaControllerCompat, activeId: Long): List<QueueEntry> {
        val items = c.queue.orEmpty().take(MAX_QUEUE)
        val active = items.indexOfFirst { it.queueId == activeId }.coerceAtLeast(0)
        return items.mapIndexed { index, item ->
            val d = item.description
            // Most players send only an art URI; fetch the ones around the playing track
            val uri = d.iconUri?.takeIf { index in active - ART_BEHIND..active + ART_AHEAD }
            QueueEntry(
                id = item.queueId,
                title = d.title?.toString().orEmpty(),
                subtitle = d.subtitle?.toString().orEmpty(),
                icon = d.iconBitmap?.asImageBitmap() ?: uri?.let(::queueArt),
            )
        }
    }

    /** Cached art for [uri], or null while it loads in the background (then the queue is republished). */
    private fun queueArt(uri: Uri): ImageBitmap? {
        val key = uri.toString()
        queueArtCache.get(key)?.let { return it }
        // Players may create the art on demand: retry a failed one after a while
        val failedAt = queueArtFailed[key]
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < ART_RETRY_MS) return null
        if (!queueArtLoading.add(key)) return null
        scope.launch {
            val art = withContext(artDispatcher) {
                runCatching {
                    val source = ImageDecoder.createSource(appContext.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val size = info.size
                        decoder.setTargetSampleSize((minOf(size.width, size.height) / QUEUE_ART_PX).coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }.asImageBitmap()
                }.onFailure { Log.i(TAG, "Queue art $uri: $it") }.getOrNull()
            }
            queueArtLoading.remove(key)
            if (art == null) {
                queueArtFailed[key] = SystemClock.elapsedRealtime()
                return@launch
            }
            queueArtFailed.remove(key)
            queueArtCache.put(key, art)
            // Many arrive together: one republish for the batch
            main.removeCallbacks(republish)
            main.postDelayed(republish, 150)
        }
        return null
    }

    private val republish = Runnable { publish() }

    /** Custom action icons are resources of the player app. */
    private fun actionIcon(packageName: String, resId: Int): ImageBitmap? {
        if (resId == 0) return null
        val key = "$packageName:$resId"
        return actionIcons.getOrPut(key) {
            runCatching {
                val res = appContext.packageManager.getResourcesForApplication(packageName)
                ResourcesCompat.getDrawable(res, resId, null)?.toBitmap(ACTION_ICON_PX, ACTION_ICON_PX)?.asImageBitmap()
            }.getOrNull()
        }
    }

    private fun nextInQueue(c: MediaControllerCompat): String? {
        val queue = c.queue ?: return null
        val activeId = c.playbackState?.activeQueueItemId ?: return null
        val index = queue.indexOfFirst { it.queueId == activeId }
        if (index < 0) return null
        val next = queue.getOrNull(index + 1)?.description ?: return null
        return listOfNotNull(next.title, next.subtitle).map { it.toString() }.filter { it.isNotBlank() }
            .joinToString(" · ").ifEmpty { null }
    }

    /** Extracts the album color off the main thread, then republishes. */
    private fun updateAccent(art: Bitmap?, track: String?) {
        if (art == null) return
        val key = "$track|${art.width}x${art.height}"
        if (key == accentKey) return
        accentKey = key
        accent = null
        scope.launch {
            val color = withContext(Dispatchers.Default) {
                val palette = Palette.from(art).maximumColorCount(16).generate()
                (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb
            }
            if (accentKey == key) {
                accent = color?.let { Color(it) }
                publish()
            }
        }
    }

    private fun MediaMetadataCompat.text(key: String): String? = getString(key)?.takeIf { it.isNotBlank() }
}
