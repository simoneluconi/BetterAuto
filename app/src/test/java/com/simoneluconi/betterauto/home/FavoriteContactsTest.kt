package com.simoneluconi.betterauto.home

import android.provider.ContactsContract.CommonDataKinds.Phone
import com.simoneluconi.betterauto.phone.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteContactsTest {
    private val mobile = PhoneNumber("+39 333 0000001", "Mobile", primary = false, type = Phone.TYPE_MOBILE)
    private val work = PhoneNumber("+39 02 0000002", "Work", primary = false, type = Phone.TYPE_WORK)
    private val mobile2 = PhoneNumber("+39 333 0000003", "Mobile", primary = false, type = Phone.TYPE_MOBILE)

    @Test
    fun callsStraightAwayOnlyWhenTheNumberIsClear() {
        val defaultWork = work.copy(primary = true)
        assertEquals(defaultWork, FavoriteContacts.preferredNumber(listOf(mobile, defaultWork)))
        assertEquals(work, FavoriteContacts.preferredNumber(listOf(work)))
        assertEquals(mobile, FavoriteContacts.preferredNumber(listOf(work, mobile)))
        assertNull(FavoriteContacts.preferredNumber(listOf(mobile, mobile2)))
        assertNull(FavoriteContacts.preferredNumber(emptyList()))
    }

    @Test
    fun avatarsAreAsBigAsTheSlotAllows() {
        // A wide, low slot: one row of big avatars
        val (big, columns, count) = FavoriteContacts.layout(4, 900f, 200f)
        assertEquals(4, count)
        assertTrue(columns >= 4)
        assertTrue(big >= 120f)
        // More favorites than fit: the smallest avatars and as many as there is room for
        val (small, _, shown) = FavoriteContacts.layout(40, 400f, 300f)
        assertEquals(72f, small)
        assertTrue(shown in 1 until 40)
    }
}
