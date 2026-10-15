package com.simoneluconi.betterauto.aap

import aap_protobuf.service.mediaplayback.MediaPlaybackStatusMessageIdOuterClass.MediaPlaybackStatusMessageId
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.protobuf.ByteString
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.media.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Now playing for the car's own displays (instrument cluster, driver info screen): everything the
 * protocol carries, i.e. title, artist, album, cover, playlist, duration, then state, source app,
 * position, shuffle and repeat. The position is refreshed every second while playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaStatusChannel(session: AapSession, channelId: Int) : ServiceChannel(session, channelId) {
    override val name = "media status"

    // Off the main thread: sending waits for the USB link
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var sentMetadata: String? = null
    private var sentStatus: MediaPlaybackStatus? = null
    private var artKey: String? = null
    private var art: ByteString? = null

    override fun onOpened() {
        scope.launch {
            NowPlaying.track.collectLatest { track ->
                sendMetadata(track)
                do {
                    sendStatus(track)
                    if (track?.playing != true) break
                    // Wake up right after the next whole second of the track
                    val position = track.positionAt(SystemClock.elapsedRealtime())
                    delay(((1000 - position % 1000) / track.speed.coerceAtLeast(0.1f)).toLong().coerceIn(50, 1000) + 20)
                } while (true)
            }
        }
    }

    override fun onChannelMessage(msg: AapMessage) {
        EventLog.log("Media status: unhandled message ${msg.id} (${msg.payload.size} bytes)")
    }

    private suspend fun sendMetadata(track: Track?) {
        if (track == null) return
        val key = listOf(track.packageName, track.title, track.artist, track.album, track.playlist, track.durationMs).joinToString("|")
        val cover = cover(track, key)
        val fullKey = "$key|${cover?.size()}"
        if (fullKey == sentMetadata) return
        sentMetadata = fullKey
        val metadata = MediaPlaybackMetadata.newBuilder()
            .setSong(track.title)
            .setArtist(track.artist)
            .setAlbum(track.album)
            .setDurationSeconds((track.durationMs / 1000).toInt())
        track.playlist?.let { metadata.setPlaylist(it) }
        cover?.let { metadata.setAlbumArt(it) }
        send(MediaPlaybackStatusMessageId.MEDIA_PLAYBACK_METADATA_VALUE, metadata.build())
        EventLog.log("Media status: ${track.title} · ${track.artist} · ${track.album}, cover ${cover?.size() ?: 0} bytes")
    }

    private fun sendStatus(track: Track?) {
        val status = MediaPlaybackStatus.newBuilder()
        if (track == null) {
            status.setState(MediaPlaybackStatus.State.STOPPED)
        } else {
            status
                .setState(if (track.playing) MediaPlaybackStatus.State.PLAYING else MediaPlaybackStatus.State.PAUSED)
                .setMediaSource(track.appLabel)
                .setPlaybackSeconds((track.positionAt(SystemClock.elapsedRealtime()) / 1000).toInt())
                .setShuffle(track.shuffle)
                .setRepeat(track.repeat && !track.repeatOne)
                .setRepeatOne(track.repeatOne)
        }
        val message = status.build()
        if (message == sentStatus) return
        sentStatus = message
        send(MediaPlaybackStatusMessageId.MEDIA_PLAYBACK_STATUS_VALUE, message)
    }

    /** The cover as JPEG, encoded once per track; small enough for any head unit. */
    private suspend fun cover(track: Track, key: String): ByteString? {
        val image = track.art ?: return null
        // The cover often arrives after the text, and a bigger one may follow: key on its size too
        val coverKey = "$key|${image.width}x${image.height}"
        if (coverKey == artKey) return art
        val encoded = withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = image.asAndroidBitmap()
                val scale = minOf(1f, COVER_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else {
                    bitmap
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                ByteString.copyFrom(out.toByteArray())
            }.getOrNull()
        }
        artKey = coverKey
        art = encoded
        return encoded
    }

    override fun close() = scope.cancel()

    private companion object {
        const val COVER_PX = 512
    }
}
