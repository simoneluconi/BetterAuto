package com.simoneluconi.betterauto.transport.wireless

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessResumeTest {
    private val now = 1_000_000_000L

    @Test
    fun killedForMemoryAMomentAgoConnectsAgain() {
        assertTrue(WirelessResume.shouldResume(ApplicationExitInfo.REASON_LOW_MEMORY, now - 13_000, now, autoConnect = true))
        assertTrue(WirelessResume.shouldResume(ApplicationExitInfo.REASON_SIGNALED, now - 60_000, now, autoConnect = true))
    }

    @Test
    fun notWithAutoConnectOff() {
        assertFalse(WirelessResume.shouldResume(ApplicationExitInfo.REASON_LOW_MEMORY, now - 13_000, now, autoConnect = false))
    }

    @Test
    fun notWhenTheUserOrACrashEndedIt() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR,
        )) assertFalse("reason $reason", WirelessResume.shouldResume(reason, now - 13_000, now, autoConnect = true))
    }

    @Test
    fun notLongAfter() {
        // The car has most likely been left: the user opened the app
        assertFalse(WirelessResume.shouldResume(ApplicationExitInfo.REASON_LOW_MEMORY, now - 10 * 60_000, now, autoConnect = true))
        // A clock gone backwards says nothing sure
        assertFalse(WirelessResume.shouldResume(ApplicationExitInfo.REASON_LOW_MEMORY, now + 60_000, now, autoConnect = true))
    }
}
