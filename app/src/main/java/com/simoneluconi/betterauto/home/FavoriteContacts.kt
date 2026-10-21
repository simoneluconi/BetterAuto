package com.simoneluconi.betterauto.home

import android.provider.ContactsContract.CommonDataKinds.Phone
import com.simoneluconi.betterauto.phone.Contact
import com.simoneluconi.betterauto.phone.PhoneNumber
import kotlinx.coroutines.flow.MutableStateFlow

/** The favourites widget's choices: which number a tap calls. */
object FavoriteContacts {
    /** Pretend favourites (debug), for screenshots without personal data; tapping them calls nobody. */
    val demo = MutableStateFlow<List<Contact>?>(null)

    /**
     * The number to call straight away: the one marked as default, the only one, or the only mobile.
     * Null when the driver has to choose (two mobiles, home and work...).
     */
    fun preferredNumber(numbers: List<PhoneNumber>): PhoneNumber? =
        numbers.firstOrNull { it.primary }
            ?: numbers.singleOrNull()
            ?: numbers.singleOrNull { it.type == Phone.TYPE_MOBILE }

    /**
     * How many favourites fit in a [width] x [height] area (dp) and how big they can be: the biggest avatar
     * that still shows them all, else the smallest one and as many as fit. Returns (avatar size, columns, count).
     */
    fun layout(count: Int, width: Float, height: Float): Triple<Float, Int, Int> {
        var fallback = Triple(MIN_AVATAR, 1, 0)
        var size = MAX_AVATAR
        while (size >= MIN_AVATAR) {
            val tileWidth = maxOf(size, MIN_TILE_WIDTH) + GAP
            val tileHeight = size + LABEL_HEIGHT + GAP
            val columns = ((width + GAP) / tileWidth).toInt().coerceAtLeast(1)
            val rows = ((height + GAP) / tileHeight).toInt()
            val capacity = columns * rows
            if (capacity >= count && rows > 0) return Triple(size, columns, count)
            fallback = Triple(size, columns, minOf(count, capacity))
            size -= STEP
        }
        return fallback
    }

    private const val MAX_AVATAR = 128f
    private const val MIN_AVATAR = 72f
    private const val STEP = 8f
    /** Room for a first name under the avatar. */
    private const val MIN_TILE_WIDTH = 120f
    /** The name under the avatar, the gap above it and the tile's own padding. */
    private const val LABEL_HEIGHT = 44f
    const val GAP = 14f
}
