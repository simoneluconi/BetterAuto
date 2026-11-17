package com.simoneluconi.betterauto.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton

/**
 * Which of a player's own buttons matter most in a car, where only a couple fit next to the app's name: liking
 * the song first, then the jumps of podcasts and audiobooks, then the rest in the player's own order. Told by the
 * kind of icon a Media3 session gives, else by the button's name (older sessions, Spotify's among them, give only
 * that). Shuffle and repeat are ours already: never shown twice.
 */
@OptIn(UnstableApi::class)
object PlayerButtons {
    /** Not shown: covered by our own buttons. */
    const val HIDDEN = Int.MAX_VALUE

    private const val LIKE = 0
    private const val JUMP = 1
    private const val DISLIKE = 2
    private const val RADIO = 3
    private const val LISTS = 4
    private const val OTHER = 5

    /** Where the player itself puts a button in its menu only: after its others of the same kind. */
    private const val OVERFLOW_PENALTY = 10

    private val Like = setOf(
        CommandButton.ICON_HEART_FILLED, CommandButton.ICON_HEART_UNFILLED,
        CommandButton.ICON_STAR_FILLED, CommandButton.ICON_STAR_UNFILLED,
        CommandButton.ICON_THUMB_UP_FILLED, CommandButton.ICON_THUMB_UP_UNFILLED,
        CommandButton.ICON_BOOKMARK_FILLED, CommandButton.ICON_BOOKMARK_UNFILLED,
    )
    private val Jump = setOf(
        CommandButton.ICON_SKIP_FORWARD, CommandButton.ICON_SKIP_FORWARD_5, CommandButton.ICON_SKIP_FORWARD_10,
        CommandButton.ICON_SKIP_FORWARD_15, CommandButton.ICON_SKIP_FORWARD_30,
        CommandButton.ICON_SKIP_BACK, CommandButton.ICON_SKIP_BACK_5, CommandButton.ICON_SKIP_BACK_10,
        CommandButton.ICON_SKIP_BACK_15, CommandButton.ICON_SKIP_BACK_30,
        CommandButton.ICON_FAST_FORWARD, CommandButton.ICON_REWIND,
    )
    private val Dislike = setOf(CommandButton.ICON_THUMB_DOWN_FILLED, CommandButton.ICON_THUMB_DOWN_UNFILLED)
    private val Lists = setOf(
        CommandButton.ICON_PLAYLIST_ADD, CommandButton.ICON_PLAYLIST_REMOVE,
        CommandButton.ICON_QUEUE_ADD, CommandButton.ICON_QUEUE_NEXT, CommandButton.ICON_QUEUE_REMOVE,
    )
    private val Ours = setOf(
        CommandButton.ICON_SHUFFLE_ON, CommandButton.ICON_SHUFFLE_OFF, CommandButton.ICON_SHUFFLE_STAR,
        CommandButton.ICON_REPEAT_ALL, CommandButton.ICON_REPEAT_ONE, CommandButton.ICON_REPEAT_OFF,
    )

    // Names as the apps write them, in English and Italian; checked in this order ("non mi piace" before "mi piace")
    private val OursWords = listOf("shuffle", "repeat", "casuale", "ripeti", "ripetizione")
    private val DislikeWords = listOf("dislike", "thumbs down", "thumb down", "non mi piace")
    // Not "star": "Start radio" would pass for a like
    private val LikeWords = listOf("like", "favorit", "favourit", "heart", "love", "preferit", "mi piace", "save", "salva")
    private val JumpWords = listOf("skip", "seek", "rewind", "forward", "avanti", "indietro", "riavvolgi")
    private val RadioWords = listOf("radio")
    private val ListsWords = listOf("playlist", "queue", "coda")

    /** Lower first; [HIDDEN] for shuffle and repeat. [overflowOnly]: the player puts it only in its menu. */
    fun rank(icon: Int, name: String, overflowOnly: Boolean): Int {
        val kind = kindOf(icon) ?: kindOf(name.lowercase())
        return if (kind == HIDDEN) HIDDEN else kind + if (overflowOnly) OVERFLOW_PENALTY else 0
    }

    private fun kindOf(icon: Int): Int? = when (icon) {
        in Ours -> HIDDEN
        in Like -> LIKE
        in Jump -> JUMP
        in Dislike -> DISLIKE
        CommandButton.ICON_RADIO -> RADIO
        in Lists -> LISTS
        else -> null
    }

    private fun kindOf(name: String): Int = when {
        OursWords.any { it in name } -> HIDDEN
        DislikeWords.any { it in name } -> DISLIKE
        LikeWords.any { it in name } -> LIKE
        JumpWords.any { it in name } -> JUMP
        RadioWords.any { it in name } -> RADIO
        ListsWords.any { it in name } -> LISTS
        else -> OTHER
    }

    /** The [max] that matter most, in the player's own order among equals. */
    fun pick(actions: List<MediaAction>, max: Int): List<MediaAction> = actions
        .filter { it.rank != HIDDEN }
        .distinctBy { it.action }
        .sortedBy { it.rank }
        .take(max.coerceAtLeast(0))
}
