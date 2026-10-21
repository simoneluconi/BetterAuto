package com.simoneluconi.betterauto.notifications

import com.simoneluconi.betterauto.settings.NotificationPrivacy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationContentTest {
    @Test
    fun onAndOffIgnoreWhoIsAboard() {
        for (others in listOf(true, false, null)) {
            assertTrue(NotificationContent.hidden(NotificationPrivacy.ON, others))
            assertFalse(NotificationContent.hidden(NotificationPrivacy.OFF, others))
        }
    }

    @Test
    fun autoHidesOnlyWhenSomeoneElseIsAboard() {
        assertTrue(NotificationContent.hidden(NotificationPrivacy.AUTO, true))
        assertFalse(NotificationContent.hidden(NotificationPrivacy.AUTO, false))
    }

    @Test
    fun autoWithoutOccupancyDoesWhatTheOldDefaultDid() {
        // The setting used to be off by default: contents shown
        assertFalse(NotificationContent.hidden(NotificationPrivacy.AUTO, null))
    }
}
