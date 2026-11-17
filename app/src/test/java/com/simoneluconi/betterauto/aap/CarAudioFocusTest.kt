package com.simoneluconi.betterauto.aap

import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import com.simoneluconi.betterauto.aap.CarAudioFocus.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAudioFocusTest {
    private val sent = mutableListOf<AudioFocusRequestType>()
    private val focus = CarAudioFocus { sent += it }

    private fun answer(state: AudioFocusStateType) = focus.onNotification(state, unsolicited = false)

    @Test
    fun aPromptAloneAsksForAMomentAndGivesItBack() {
        focus.guidanceStarted()
        focus.request(Owner.GUIDANCE, AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK)
        assertEquals(setOf(Owner.GUIDANCE), answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT))
        assertTrue(focus.granted)
        focus.release(Owner.GUIDANCE)
        assertEquals(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, sent.last())
        assertFalse(focus.granted)
    }

    @Test
    fun aPromptOverTheMusicAsksForItsMomentAndTheMusicAsksBackNeverReleasing() {
        focus.request(Owner.MEDIA, AudioFocusRequestType.AUDIO_FOCUS_GAIN)
        assertEquals(setOf(Owner.MEDIA), answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN))
        focus.mediaGained()
        focus.guidanceStarted()
        focus.request(Owner.GUIDANCE, AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK)
        assertEquals(setOf(Owner.GUIDANCE), answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY))
        focus.release(Owner.GUIDANCE)
        assertEquals(
            listOf(
                AudioFocusRequestType.AUDIO_FOCUS_GAIN,
                AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK,
                AudioFocusRequestType.AUDIO_FOCUS_GAIN,
            ),
            sent,
        )
        // The car's answer to the music asking back is the music's
        assertEquals(setOf(Owner.MEDIA), answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN))
        assertTrue(focus.granted)
    }

    @Test
    fun musicStoppingDuringAPromptReleasesOnlyAfterIt() {
        focus.request(Owner.MEDIA, AudioFocusRequestType.AUDIO_FOCUS_GAIN)
        answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
        focus.mediaGained()
        focus.guidanceStarted()
        focus.release(Owner.MEDIA)
        assertEquals(1, sent.size)
        focus.release(Owner.GUIDANCE)
        assertEquals(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, sent.last())
    }

    @Test
    fun theCarTakingTheSoundConcernsBothAndNothingIsReleasedAfter() {
        focus.request(Owner.MEDIA, AudioFocusRequestType.AUDIO_FOCUS_GAIN)
        answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
        focus.mediaGained()
        val to = focus.onNotification(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS, unsolicited = true)
        assertEquals(Owner.entries.toSet(), to)
        assertFalse(focus.granted)
        focus.release(Owner.MEDIA)
        assertEquals(1, sent.size)
    }

    @Test
    fun duckingKeepsTheFocus() {
        focus.request(Owner.MEDIA, AudioFocusRequestType.AUDIO_FOCUS_GAIN)
        answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
        focus.onNotification(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK, unsolicited = true)
        assertTrue(focus.granted)
    }

    @Test
    fun afterAVoiceCommandTheMusicAsksAgainOnlyIfItHoldsTheFocus() {
        assertFalse(focus.reclaimForMusic())
        focus.request(Owner.MEDIA, AudioFocusRequestType.AUDIO_FOCUS_GAIN)
        answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
        focus.mediaGained()
        assertTrue(focus.reclaimForMusic())
        assertEquals(listOf(AudioFocusRequestType.AUDIO_FOCUS_GAIN, AudioFocusRequestType.AUDIO_FOCUS_GAIN), sent)
        assertEquals(setOf(Owner.MEDIA), answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN))
        // Taken by the car meanwhile: nothing to ask back
        focus.onNotification(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS, unsolicited = true)
        assertFalse(focus.reclaimForMusic())
    }

    @Test
    fun theReleaseAnswerGoesToWhoeverReleased() {
        focus.guidanceStarted()
        focus.request(Owner.GUIDANCE, AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK)
        answer(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY)
        focus.release(Owner.GUIDANCE)
        assertEquals(setOf(Owner.GUIDANCE), answer(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS))
    }
}
