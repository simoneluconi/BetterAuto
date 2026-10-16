package com.simoneluconi.betterauto.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.session.MediaController as PlatformController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.simoneluconi.betterauto.EventLog
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
    /** The player accepts seeking to a position. */
    val canSeek: Boolean,
) {
    fun positionAt(now: Long): Long {
        val elapsed = if (playing) ((now - positionUpdatedAt) * speed).toLong() else 0L
        val position = positionMs + elapsed
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

/** [id] is the item's position in the player's queue. */
data class QueueEntry(val id: Long, val title: String, val subtitle: String, val icon: ImageBitmap?)

data class MediaAction(val action: String, val name: String, val icon: ImageBitmap?)

/**
 * Follows the phone's active media session (any app: Spotify, YouTube Music, ...) and controls it
 * through a Media3 controller, which speaks both to Media3 sessions and to the older ones.
 * Needs notification access for [MediaListenerService]; [hasAccess] reports whether we have it.
 * Main thread only, except [pause], which hops there by itself.
 */
@OptIn(UnstableApi::class)
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

    // The controller of the session being followed, the platform token it was built from, and a
    // generation number so a slow connection cannot install a controller for a session already left
    private var controller: MediaController? = null
    private var attachedToken: MediaSession.Token? = null
    private var generation = 0

    private val actionIcons = HashMap<String, ImageBitmap?>()
    private const val MAX_QUEUE = 200
    private const val ACTION_ICON_PX = 96
    private const val QUEUE_ART_PX = 128
    private const val ART_PX = 1024
    private const val ART_BEHIND = 5
    private const val ART_AHEAD = 40
    private const val TAG = "BetterAuto"
    private val queueArtCache = LruCache<String, ImageBitmap>(80)
    private val queueArtLoading = HashSet<String>()
    private val queueArtFailed = HashMap<String, Long>()
    private const val ART_RETRY_MS = 15_000L
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val artDispatcher = Dispatchers.IO.limitedParallelism(3)

    // The album art as the player sent it (bytes or URI, by identity) and its screen-sized copy
    private var loggedQueueSize = -1
    private var artKey: Any? = null
    private var artImage: ImageBitmap? = null
    private var accent: Color? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { onSessionsChanged(it) }

    private val playerListener = object : Player.Listener {
        // One callback per batch of changes (state, metadata, queue, modes...)
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (controller === this@NowPlaying.controller) refresh()
        }

        override fun onMediaButtonPreferencesChanged(controller: MediaController, mediaButtonPreferences: MutableList<CommandButton>) = publish()

        override fun onAvailableSessionCommandsChanged(controller: MediaController, commands: SessionCommands) = publish()
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
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNext()

    /** The one control callable from any thread: a disconnect pauses before the phone volume comes back. */
    fun pause() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { pause() }
            return
        }
        val c = controller ?: return
        if (c.isPlaying) c.pause()
    }

    /** Resumes the current player, or starts the most recently used music app if none is active. */
    fun resume() {
        val c = controller
        if (c != null) {
            if (!c.isPlaying) c.play()
            return
        }
        LauncherSettings.get(appContext).recentApps.value.firstOrNull()?.let { open(it) }
    }

    fun previous() = controller?.seekToPrevious()

    /** Standard shuffle when the player supports it, otherwise the player's own "shuffle" button. */
    fun toggleShuffle() {
        val c = controller ?: return
        if (c.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
            c.shuffleModeEnabled = !c.shuffleModeEnabled
        } else {
            customButtonNamed(c, "shuffle")?.let { sendCustomCommand(c, it) }
        }
    }

    /** Standard repeat when the player supports it, otherwise the player's own "repeat" button. */
    fun toggleRepeat() {
        val c = controller ?: return
        if (c.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
            c.repeatMode = if (c.repeatMode != Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        } else {
            customButtonNamed(c, "repeat")?.let { sendCustomCommand(c, it) }
        }
    }

    fun skipToQueueItem(id: Long) {
        val c = controller ?: return
        if (id in 0 until c.currentTimeline.windowCount) c.seekToDefaultPosition(id.toInt())
    }

    fun sendCustomAction(action: String) {
        val c = controller ?: return
        c.mediaButtonPreferences.firstOrNull { it.sessionCommand?.customAction == action }?.let { sendCustomCommand(c, it) }
    }

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    val isPlaying: Boolean get() = controller?.isPlaying == true

    /**
     * Asks the current player to find and play [query] (an artist, a song, a playlist), like a voice request.
     * Through the session's own "play from search": Media3's way (a search-only item set on our controller)
     * leaves our controller holding that empty item, so the screen would lose the title and the cover.
     */
    fun playFromSearch(query: String): Boolean {
        val c = controller ?: return false
        val platform = sessionManager?.let { activeSessions(it) }?.firstOrNull { it.packageName == _track.value?.packageName }
        if (platform != null && (platform.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
            platform.transportControls.playFromSearch(query, Bundle())
            return true
        }
        c.setMediaItem(
            MediaItem.Builder()
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
                .build()
        )
        c.prepare()
        c.play()
        return true
    }

    private fun sendCustomCommand(c: MediaController, button: CommandButton) {
        button.sessionCommand?.let { c.sendCustomCommand(it, Bundle.EMPTY) }
    }

    private fun customButtonNamed(c: MediaController, word: String) =
        c.mediaButtonPreferences.firstOrNull { it.displayName.toString().contains(word, ignoreCase = true) }

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

    private fun activeSessions(msm: MediaSessionManager): List<PlatformController> =
        try {
            msm.getActiveSessions(listenerComponent())
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun listenerComponent() = ComponentName(appContext, MediaListenerService::class.java)

    private fun onSessionsChanged(sessions: List<PlatformController>?) {
        val list = sessions.orEmpty()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken == attachedToken) {
            publish()
            return
        }
        attach(best)
    }

    /** Builds a controller for [session]; the screen keeps the previous track until it is connected. */
    private fun attach(session: PlatformController?) {
        generation++
        controller?.let {
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
        attachedToken = session?.sessionToken
        if (session == null) {
            publish()
            return
        }
        val expected = generation
        val tokenFuture = SessionToken.createSessionToken(appContext, session.sessionToken)
        tokenFuture.addListener({
            if (expected != generation) return@addListener
            val token = runCatching { tokenFuture.get() }.getOrNull() ?: return@addListener
            val controllerFuture = MediaController.Builder(appContext, token).setListener(controllerListener).buildAsync()
            controllerFuture.addListener({
                val built = runCatching { controllerFuture.get() }
                    .onFailure { Log.i(TAG, "Media controller for ${session.packageName}: $it") }
                    .getOrNull()
                if (expected != generation || built == null) {
                    built?.release()
                    return@addListener
                }
                controller = built
                built.addListener(playerListener)
                val kind = if (token.type == SessionToken.TYPE_SESSION) "Media3 session" else "legacy session"
                EventLog.log("Now playing: following ${session.packageName} ($kind)")
                EventLog.log(
                    "Now playing: legacy queue ${session.queue?.size ?: "none"}, timeline ${built.currentTimeline.windowCount}, " +
                        "commands timeline=${built.isCommandAvailable(Player.COMMAND_GET_TIMELINE)} " +
                        "seekItem=${built.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)} " +
                        "custom=${built.availableSessionCommands.commands.count { it.commandCode == SessionCommand.COMMAND_CODE_CUSTOM }} " +
                        "buttons=${built.mediaButtonPreferences.size}"
                )
                publish()
            }, appContext.mainExecutor)
        }, appContext.mainExecutor)
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            // Nothing to follow; while a controller is still connecting the previous track stays
            if (attachedToken == null) _track.value = null
            return
        }
        val packageName = c.connectedToken?.packageName ?: return
        val metadata = c.mediaMetadata
        val playing = c.isPlaying
        updateArt(metadata, "${metadata.albumTitle}|${metadata.title}")
        val app = MediaApps.info(appContext, packageName)
        if (playing) LauncherSettings.get(appContext).markRecent(packageName)
        val timeline = c.currentTimeline
        val active = if (timeline.isEmpty) -1 else c.currentMediaItemIndex
        if (timeline.windowCount != loggedQueueSize) {
            loggedQueueSize = timeline.windowCount
            EventLog.log("Now playing: queue of ${timeline.windowCount} items, playing #${active + 1}")
        }

        _track.value = Track(
            packageName = packageName,
            appLabel = app?.label ?: packageName,
            appIcon = app?.icon,
            title = metadata.title.text() ?: metadata.displayTitle.text() ?: "",
            artist = metadata.artist.text() ?: metadata.albumArtist.text() ?: metadata.subtitle.text() ?: "",
            album = metadata.albumTitle.text() ?: "",
            art = artImage,
            accent = if (artKey != null) accent else null,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L,
            positionMs = c.currentPosition.coerceAtLeast(0),
            positionUpdatedAt = SystemClock.elapsedRealtime(),
            speed = c.playbackParameters.speed.takeIf { it > 0f } ?: 1f,
            playing = playing,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode != Player.REPEAT_MODE_OFF,
            repeatOne = c.repeatMode == Player.REPEAT_MODE_ONE,
            playlist = c.playlistMetadata.title.text(),
            upNext = nextInQueue(timeline, active),
            queue = queueEntries(timeline, active),
            activeQueueId = active.toLong(),
            customActions = c.mediaButtonPreferences.mapNotNull { button ->
                val command = button.sessionCommand?.takeIf { it.commandCode == SessionCommand.COMMAND_CODE_CUSTOM }
                    ?: return@mapNotNull null
                MediaAction(command.customAction, button.displayName.toString(), actionIcon(packageName, button.iconResId))
            },
            canSeek = c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        )
        rememberArtist(_track.value?.artist)
    }

    private val artists = ArrayDeque<String>()

    /** Artists heard since the app started, most recent first (the main one of each song). */
    val recentArtists: List<String> get() = artists.toList()

    private fun rememberArtist(artist: String?) {
        val main = artist?.split(ArtistSeparators)?.firstOrNull()?.trim()?.takeIf { it.length > 1 } ?: return
        if (artists.firstOrNull() == main) return
        artists.remove(main)
        artists.addFirst(main)
        while (artists.size > MAX_ARTISTS) artists.removeLast()
    }

    private val ArtistSeparators = Regex("\\s*(,|/|&|;|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)
    private const val MAX_ARTISTS = 30

    private fun queueEntries(timeline: Timeline, active: Int): List<QueueEntry> {
        val window = Timeline.Window()
        val count = minOf(timeline.windowCount, MAX_QUEUE)
        return (0 until count).map { index ->
            val item = timeline.getWindow(index, window).mediaItem
            val m = item.mediaMetadata
            // Art is decoded only around the playing track; players send bytes or a URI
            val wantArt = index in active - ART_BEHIND..active + ART_AHEAD
            QueueEntry(
                id = index.toLong(),
                title = m.title.text().orEmpty(),
                subtitle = m.subtitle.text() ?: m.artist.text().orEmpty(),
                icon = if (wantArt) queueArt("${item.mediaId}|${m.artworkData?.size ?: m.artworkUri}", m.artworkData, m.artworkUri) else null,
            )
        }
    }

    /** Cached art for [key], or null while it loads in the background (then the queue is republished). */
    private fun queueArt(key: String, data: ByteArray?, uri: Uri?): ImageBitmap? {
        if (data == null && uri == null) return null
        queueArtCache.get(key)?.let { return it }
        // Players may create the art on demand: retry a failed one after a while
        val failedAt = queueArtFailed[key]
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < ART_RETRY_MS) return null
        if (!queueArtLoading.add(key)) return null
        scope.launch {
            val art = withContext(artDispatcher) { Artwork.decode(appContext, data, uri, QUEUE_ART_PX)?.asImageBitmap() }
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

    private fun nextInQueue(timeline: Timeline, active: Int): String? {
        if (active < 0 || active + 1 >= timeline.windowCount) return null
        val next = timeline.getWindow(active + 1, Timeline.Window()).mediaItem.mediaMetadata
        return listOfNotNull(next.title.text(), next.subtitle.text() ?: next.artist.text()).joinToString(" · ").ifEmpty { null }
    }

    /**
     * Album art and accent color, computed once per artwork the player sends, off the main thread.
     * Covers of thousands of pixels from local players are brought down to a size the screen can use.
     */
    private fun updateArt(metadata: MediaMetadata, track: String) {
        val data = metadata.artworkData
        val uri = metadata.artworkUri
        val key: Any? = data ?: uri
        if (key === artKey || (key != null && key == artKey)) return
        artKey = key
        artImage = null
        accent = null
        if (key == null) return
        scope.launch {
            val (image, color) = withContext(Dispatchers.Default) {
                val bitmap = Artwork.decode(appContext, data, uri, ART_PX)
                val swatch = bitmap?.let { Palette.from(it).maximumColorCount(16).generate() }
                    ?.let { it.vibrantSwatch ?: it.dominantSwatch }
                bitmap?.asImageBitmap() to swatch?.rgb?.let { Color(it) }
            }
            if (artKey !== key) return@launch
            artImage = image
            accent = color
            publish()
        }
    }

    private fun CharSequence?.text(): String? = this?.toString()?.takeIf { it.isNotBlank() }
}
