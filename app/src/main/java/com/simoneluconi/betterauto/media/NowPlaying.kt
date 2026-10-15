@file:Suppress("DEPRECATION") // androidx.media compat APIs; to be migrated to Media3

package com.simoneluconi.betterauto.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.palette.graphics.Palette
import com.simoneluconi.betterauto.settings.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val upNext: String?,
) {
    fun positionAt(now: Long): Long {
        val elapsed = if (playing) ((now - positionUpdatedAt) * speed).toLong() else 0L
        val position = positionMs + elapsed
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

/**
 * Follows the phone's active media session (any app: Spotify, YouTube Music, ...) and controls it.
 * Needs notification access for [MediaListenerService]; [hasAccess] reports whether we have it.
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
    private var controller: MediaControllerCompat? = null
    // Each metadata read hands out a new Bitmap copy, so the art is identified by track + size, not by instance
    private var accentKey: String? = null
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

    fun previous() = controller?.transportControls?.skipToPrevious()

    fun toggleShuffle() {
        val c = controller ?: return
        val on = c.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
        c.transportControls.setShuffleMode(
            if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL
        )
    }

    fun toggleRepeat() {
        val c = controller ?: return
        val on = c.repeatMode != PlaybackStateCompat.REPEAT_MODE_NONE
        c.transportControls.setRepeatMode(
            if (on) PlaybackStateCompat.REPEAT_MODE_NONE else PlaybackStateCompat.REPEAT_MODE_ALL
        )
    }

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
            upNext = nextInQueue(c),
        )
    }

    private fun nextInQueue(c: MediaControllerCompat): String? {
        val queue = c.queue ?: return null
        val activeId = c.playbackState?.activeQueueItemId ?: return null
        val index = queue.indexOfFirst { it.queueId == activeId }
        if (index < 0) return null
        return queue.getOrNull(index + 1)?.description?.title?.toString()
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
