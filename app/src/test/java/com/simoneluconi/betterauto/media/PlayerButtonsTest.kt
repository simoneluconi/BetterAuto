package com.simoneluconi.betterauto.media

import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerButtonsTest {
    private fun action(name: String, icon: Int = CommandButton.ICON_UNDEFINED, overflow: Boolean = false) =
        MediaAction(name, name, null, PlayerButtons.rank(icon, name, overflow))

    private fun picked(vararg actions: MediaAction, max: Int = 2) = PlayerButtons.pick(actions.toList(), max).map { it.name }

    @Test
    fun likingTheSongComesFirstWhereverThePlayerPutsIt() {
        assertEquals(
            listOf("Add to Liked Songs", "Start radio"),
            picked(action("Add to playlist"), action("Start radio"), action("Add to Liked Songs")),
        )
    }

    @Test
    fun shuffleAndRepeatAreNeverShownTwice() {
        assertEquals(
            listOf("Favorite"),
            picked(action("Shuffle"), action("Repeat"), action("Favorite"), action("Casuale"), max = 3),
        )
        assertEquals(PlayerButtons.HIDDEN, PlayerButtons.rank(CommandButton.ICON_SHUFFLE_ON, "Mix", false))
    }

    @Test
    fun theKindOfIconWinsOverTheName() {
        assertEquals(
            listOf("Mix", "30"),
            picked(action("Something"), action("30", CommandButton.ICON_SKIP_FORWARD_30), action("Mix", CommandButton.ICON_HEART_UNFILLED)),
        )
    }

    @Test
    fun aDislikeIsNotALikeNorStartRadio() {
        assertEquals(listOf("Mi piace", "Non mi piace", "Start radio"), picked(action("Start radio"), action("Non mi piace"), action("Mi piace"), max = 3))
    }

    @Test
    fun amongEqualsThePlayersOrderAndItsMenuComeLast() {
        assertEquals(
            listOf("B", "C"),
            picked(action("A", overflow = true), action("B"), action("C")),
        )
        assertEquals(listOf("A"), picked(action("A"), action("A"), max = 3))
    }
}
