package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType

/**
 * The phone has a single audio focus toward the car, shared by its streams: the music and the
 * navigation prompts. This decides who asks, who hears the car's answer, and when the focus really
 * goes back, so that one stream ending never takes the speakers from the other:
 * - music asks for the focus to keep (GAIN);
 * - a prompt asks only when the phone holds nothing, for a moment and letting the car lower its own
 *   sources (GAIN_TRANSIENT_MAY_DUCK); while the music holds the focus it just plays over it;
 * - the focus goes back (RELEASE) only once neither stream needs it.
 *
 * No Android here: [send] puts the request on the wire.
 */
class CarAudioFocus(private val send: (AudioFocusRequestType) -> Unit) {
    enum class Owner { MEDIA, GUIDANCE }

    /** The car has granted the phone a focus (of any kind) that was not taken back or released. */
    @Volatile
    var granted = false
        private set

    private var mediaHolds = false
    private var guidancePlaying = false

    /** Who sent the request the car's next answer belongs to. */
    private var asking: Owner? = null

    @Synchronized
    fun request(owner: Owner, type: AudioFocusRequestType) {
        asking = owner
        send(type)
    }

    /** The music holds the focus: a prompt ending must not give it back. */
    @Synchronized
    fun mediaGained() {
        mediaHolds = true
    }

    /** A prompt is playing: the music stopping meanwhile must not give the focus back. */
    @Synchronized
    fun guidanceStarted() {
        guidancePlaying = true
    }

    /** [owner] no longer needs the focus; it goes back to the car only when the other stream does not need it either. */
    @Synchronized
    fun release(owner: Owner) {
        when (owner) {
            Owner.MEDIA -> mediaHolds = false
            Owner.GUIDANCE -> guidancePlaying = false
        }
        if (mediaHolds || guidancePlaying || !granted) return
        granted = false
        asking = owner
        send(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
    }

    /**
     * The car's focus notification: returns who should hear it. An answer goes to whoever asked; a change
     * the car makes by itself (its radio, a call) concerns both streams.
     */
    @Synchronized
    fun onNotification(state: AudioFocusStateType, unsolicited: Boolean): Set<Owner> {
        when (state) {
            in Gains -> granted = true
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT -> {
                granted = false
                mediaHolds = false
            }
            // Ducked: still ours, only quieter
            else -> Unit
        }
        if (unsolicited) return Owner.entries.toSet()
        val to = asking ?: Owner.MEDIA
        asking = null
        return setOf(to)
    }

    companion object {
        val Gains = setOf(
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY,
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY,
        )
    }
}
