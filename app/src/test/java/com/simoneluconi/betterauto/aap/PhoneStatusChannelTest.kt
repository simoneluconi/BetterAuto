package com.simoneluconi.betterauto.aap

import aap_protobuf.service.phonestatus.message.PhoneStatusOuterClass.PhoneStatus
import com.google.protobuf.ByteString
import com.simoneluconi.betterauto.phone.CallInfo
import com.simoneluconi.betterauto.phone.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneStatusChannelTest {
    private fun call(
        phase: CallPhase,
        id: Int = 1,
        name: String? = "Mario Rossi",
        number: String? = "+15550104567",
        connectedAt: Long = 0L,
        muted: Boolean = false,
        conference: Boolean = false,
    ) = CallInfo(
        id = id, phase = phase, name = name, number = number, photo = null, numberLabel = "Mobile", company = null,
        connectedAt = connectedAt, canHold = true, muted = muted, others = 0, conference = conference,
    )

    @Test
    fun eachPhaseHasItsState() {
        assertEquals(PhoneStatus.State.INCOMING, PhoneStatusChannel.state(call(CallPhase.RINGING)))
        assertEquals(PhoneStatus.State.IN_CALL, PhoneStatusChannel.state(call(CallPhase.DIALING)))
        assertEquals(PhoneStatus.State.IN_CALL, PhoneStatusChannel.state(call(CallPhase.ACTIVE)))
        assertEquals(PhoneStatus.State.ON_HOLD, PhoneStatusChannel.state(call(CallPhase.HOLDING)))
        assertEquals(PhoneStatus.State.INACTIVE, PhoneStatusChannel.state(call(CallPhase.ENDED)))
    }

    @Test
    fun mutedAndConferenceOnlyChangeACallInProgress() {
        assertEquals(PhoneStatus.State.MUTED, PhoneStatusChannel.state(call(CallPhase.ACTIVE, muted = true)))
        assertEquals(PhoneStatus.State.CONFERENCED, PhoneStatusChannel.state(call(CallPhase.ACTIVE, conference = true)))
        assertEquals(PhoneStatus.State.CONFERENCED, PhoneStatusChannel.state(call(CallPhase.ACTIVE, muted = true, conference = true)))
        assertEquals(PhoneStatus.State.ON_HOLD, PhoneStatusChannel.state(call(CallPhase.HOLDING, muted = true)))
        assertEquals(PhoneStatus.State.INCOMING, PhoneStatusChannel.state(call(CallPhase.RINGING, muted = true)))
    }

    @Test
    fun theMessageCarriesCallerDurationAndSignal() {
        val snapshot = PhoneStatusChannel.snapshot(listOf(call(CallPhase.ACTIVE, connectedAt = 10_000L)), 3)
        val message = PhoneStatusChannel.message(snapshot, now = 75_500L)
        assertEquals(1, message.callsCount)
        val c = message.getCalls(0)
        assertEquals(PhoneStatus.State.IN_CALL, c.phoneState)
        assertEquals(65, c.callDurationSeconds)
        assertEquals("+15550104567", c.callerNumber)
        assertEquals("Mario Rossi", c.callerId)
        assertEquals("Mobile", c.callerNumberType)
        assertFalse(c.hasCallerThumbnail())
        assertEquals(3, message.signalStrength)
    }

    @Test
    fun unknownsAreLeftOut() {
        val snapshot = PhoneStatusChannel.snapshot(listOf(call(CallPhase.RINGING, name = null, number = "")), null)
        val message = PhoneStatusChannel.message(snapshot, now = 1_000L)
        val c = message.getCalls(0)
        assertEquals(0, c.callDurationSeconds)
        assertFalse(c.hasCallerNumber())
        assertFalse(c.hasCallerId())
        assertFalse(message.hasSignalStrength())
    }

    @Test
    fun noCallsIsAnEmptyList() {
        val message = PhoneStatusChannel.message(PhoneStatusChannel.snapshot(emptyList(), 4), now = 0L)
        assertEquals(0, message.callsCount)
        assertEquals(4, message.signalStrength)
    }

    @Test
    fun signalIsClampedToTheBars() {
        assertEquals(4, PhoneStatusChannel.snapshot(emptyList(), 7).signal)
        assertEquals(0, PhoneStatusChannel.snapshot(emptyList(), -1).signal)
    }

    @Test
    fun theSameStateIsTheSameSnapshotWhateverTheTime() {
        // The duration is worked out when sending, so time passing alone does not make a new update
        val a = PhoneStatusChannel.snapshot(listOf(call(CallPhase.ACTIVE, connectedAt = 5_000L)), 2)
        val b = PhoneStatusChannel.snapshot(listOf(call(CallPhase.ACTIVE, connectedAt = 5_000L)), 2)
        assertEquals(a, b)
        assertNotEquals(a, PhoneStatusChannel.snapshot(listOf(call(CallPhase.ACTIVE, connectedAt = 5_000L, muted = true)), 2))
        assertNotEquals(a, PhoneStatusChannel.snapshot(listOf(call(CallPhase.ACTIVE, connectedAt = 5_000L)), 3))
    }

    @Test
    fun aPhotoGoesOncePerCall() {
        val withPhoto = PhoneStatusSnapshot(
            listOf(CallStatus(1, PhoneStatus.State.INCOMING, 0L, "123", "A", null, "content://photo/1")), 4,
        )
        assertEquals(listOf(1), PhoneStatusChannel.photosToSend(withPhoto, emptyMap()).map { it.id })
        assertTrue(PhoneStatusChannel.photosToSend(withPhoto, mapOf(1 to "content://photo/1")).isEmpty())
        // Another photo for the same call (the contact was found later) goes again
        assertEquals(1, PhoneStatusChannel.photosToSend(withPhoto, mapOf(1 to "content://photo/0")).size)
    }

    @Test
    fun theLogMasksNumbersAndLeavesNamesOut() {
        val snapshot = PhoneStatusChannel.snapshot(listOf(call(CallPhase.RINGING), call(CallPhase.HOLDING, id = 2, number = "+15550109876")), 3)
        val line = PhoneStatusChannel.describe(snapshot, mapOf(1 to ByteString.copyFrom(ByteArray(10))))
        assertEquals("incoming …567, named, photo 10 bytes; on hold …876, named, signal 3", line)
        assertFalse(line.contains("Mario"))
        assertEquals("no calls, signal unknown", PhoneStatusChannel.describe(PhoneStatusChannel.snapshot(emptyList(), null)))
    }
}
