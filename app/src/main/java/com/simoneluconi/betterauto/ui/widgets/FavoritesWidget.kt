package com.simoneluconi.betterauto.ui.widgets

import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.home.FavoriteContacts
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.phone.Contact
import com.simoneluconi.betterauto.phone.PhoneBook
import com.simoneluconi.betterauto.phone.PhoneNumber
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.phone.Avatar
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The phone's starred contacts as big round avatars, as many as the slot holds: a tap calls the default
 * (or only, or only mobile) number, otherwise the numbers are listed in the card to pick one.
 * Read again when the contacts change, while the widget is on screen.
 */
@Composable
fun FavoritesWidget(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val order by settings.nameOrder.collectAsStateWithLifecycle()
    val demo by FavoriteContacts.demo.collectAsStateWithLifecycle()
    val allowed = PhoneBook.canRead(context)
    val version = rememberContentVersion(ContactsContract.Contacts.CONTENT_URI, enabled = allowed && demo == null)
    var favorites by remember { mutableStateOf<List<Contact>?>(null) }
    LaunchedEffect(version, order, allowed) {
        // A sync touches many rows at once
        if (version > 0) delay(500)
        favorites = PhoneBook.contacts(context, order, starredOnly = true)
    }
    var picking by remember { mutableStateOf<Pair<Contact, List<PhoneNumber>>?>(null) }

    fun call(contact: Contact) {
        if (contact.id < 0) {
            EventLog.log("Favorites: pretend contact, no call")
            return
        }
        scope.launch {
            val numbers = PhoneBook.numbers(context, contact.id)
            val number = FavoriteContacts.preferredNumber(numbers)
            when {
                number != null -> Calls.place(context, number.number)
                numbers.isNotEmpty() -> picking = contact to numbers
            }
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val open = picking
        if (open != null) {
            NumberList(open.first, open.second, onClose = { picking = null })
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarIcon(CarIcons.Star, colors.text2, Modifier.size(26.dp))
            CarLabel(stringResource(R.string.widget_favorites), 24.sp, colors.text, weight = FontWeight.ExtraBold)
        }
        val list = demo ?: favorites
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                demo == null && !allowed -> PanelMessage(stringResource(R.string.phone_no_access))
                list == null -> Unit
                list.isEmpty() -> PanelMessage(stringResource(R.string.phone_no_favorites))
                else -> {
                    val (avatar, columns, count) = FavoriteContacts.layout(list.size, maxWidth.value, maxHeight.value)
                    val gap = FavoriteContacts.GAP.dp
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        for (row in list.take(count.coerceAtLeast(1)).chunked(columns)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                for (contact in row) FavoriteTile(contact, avatar) { call(contact) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteTile(contact: Contact, avatar: Float, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .carClickable(shape, stringResource(R.string.phone_call_name, contact.name), onClick = onClick)
            .width(maxOf(avatar, 120f).dp)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(contact.name, contact.photoLarge ?: contact.photo, avatar.dp)
        // Smaller rather than cut: the whole name tells two Marcos apart
        CarLabel(contact.name, 19.sp, colors.text, weight = FontWeight.Bold, textAlign = TextAlign.Center, shrinkToFit = true)
    }
}

/** A favourite with several numbers and no default one: which to call. Back closes it. */
@Composable
private fun NumberList(contact: Contact, numbers: List<PhoneNumber>, onClose: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    // Opened with the knob: the focus goes on, to the first number, instead of being lost with the avatar
    LaunchedEffect(Unit) { if (keyboard) runCatching { first.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .carClickable(CircleShape, stringResource(R.string.music_back), onClick = onClose)
                    .background(colors.surface2, CircleShape)
                    .size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            Avatar(contact.name, contact.photo, 52.dp)
            CarLabel(contact.name, 22.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f).verticalScrollWithBar(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((index, number) in numbers.withIndex()) {
                ListRow(
                    icon = null,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    fallback = CarIcons.CallAnswer,
                    title = number.label,
                    subtitle = number.number,
                    highlighted = false,
                    roundIcon = true,
                ) {
                    onClose()
                    Calls.place(context, number.number)
                }
            }
        }
    }
}
