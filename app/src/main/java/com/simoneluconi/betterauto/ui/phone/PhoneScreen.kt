package com.simoneluconi.betterauto.ui.phone

import android.net.Uri
import android.provider.CallLog
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.phone.CallPhase
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.phone.Contact
import com.simoneluconi.betterauto.phone.PhoneBook
import com.simoneluconi.betterauto.phone.PhoneNumber
import com.simoneluconi.betterauto.phone.RecentCall
import com.simoneluconi.betterauto.settings.LauncherSettings
import androidx.compose.foundation.lazy.rememberLazyListState
import com.simoneluconi.betterauto.ui.AlphabetButton
import com.simoneluconi.betterauto.ui.AlphabetJumpEffect
import com.simoneluconi.betterauto.ui.AlphabetPicker
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.indexLetter
import com.simoneluconi.betterauto.ui.rememberAlphabetJump
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.widgets.ListRow
import com.simoneluconi.betterauto.ui.widgets.PanelMessage
import kotlinx.coroutines.launch

private enum class PhoneTab(@param:StringRes val label: Int, val icon: ImageVector) {
    RECENTS(R.string.phone_tab_recents, CarIcons.Clock),
    FAVORITES(R.string.phone_tab_favorites, CarIcons.Star),
    CONTACTS(R.string.phone_tab_contacts, CarIcons.Contacts),
    KEYPAD(R.string.phone_tab_keypad, CarIcons.Dialpad),
}

internal val CallGreen = Color(0xFF2E9E5B)
internal val CallRed = Color(0xFFE5484D)

/** Phone section: recents, favorites, contacts and a keypad. The open tab is remembered. */
@Composable
fun PhoneScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val savedTab by settings.phoneTab.collectAsStateWithLifecycle()
    val tab = PhoneTab.entries.firstOrNull { it.name == savedTab } ?: PhoneTab.FAVORITES
    // Contact whose numbers are being chosen
    var picking by remember { mutableStateOf<Contact?>(null) }

    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (value in PhoneTab.entries) {
                val shape = RoundedCornerShape(50)
                val text = stringResource(value.label)
                Row(
                    Modifier
                        .weight(1f)
                        .carClickable(shape, text) {
                            picking = null
                            settings.setPhoneTab(value.name)
                        }
                        .background(if (tab == value) colors.focus.copy(alpha = 0.22f) else colors.surface2, shape)
                        .height(60.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    CarIcon(value.icon, colors.text, Modifier.size(24.dp))
                    CarLabel(text, 21.sp, colors.text, weight = FontWeight.Bold)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val contact = picking
            when {
                contact != null -> NumberPicker(contact, onClose = { picking = null })
                tab == PhoneTab.KEYPAD -> Keypad(onCall = { Calls.place(context, it) })
                !PhoneBook.canRead(context) && !PhoneBook.canReadCallLog(context) ->
                    PanelMessage(stringResource(R.string.phone_no_access))
                tab == PhoneTab.RECENTS -> RecentsList(settings)
                tab == PhoneTab.FAVORITES -> FavoritesGrid(settings) { picking = it }
                tab == PhoneTab.CONTACTS -> ContactsList(settings) { picking = it }
            }
        }
    }
}

@Composable
private fun RecentsList(settings: LauncherSettings) {
    val context = LocalContext.current
    val order by settings.nameOrder.collectAsStateWithLifecycle()
    val call by Calls.current.collectAsStateWithLifecycle()
    // Reload when a call ends, so it shows up right away
    val callEnded = call?.phase == CallPhase.ENDED
    val recents by produceState<List<RecentCall>?>(null, order, callEnded) {
        value = PhoneBook.recents(context, order).also { EventLog.log("Phone: ${it.size} recent calls") }
    }
    val list = recents
    when {
        list == null -> PanelMessage(stringResource(R.string.music_loading))
        list.isEmpty() -> PanelMessage(stringResource(if (PhoneBook.canReadCallLog(context)) R.string.phone_no_recents else R.string.phone_no_access))
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { "${it.number}|${it.date}" }) { recent ->
                RecentRow(recent) { Calls.place(context, recent.number) }
            }
        }
    }
}

@Composable
private fun RecentRow(recent: RecentCall, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val title = recent.name ?: recent.number.ifBlank { stringResource(R.string.phone_unknown) }
    val missed = recent.type == CallLog.Calls.MISSED_TYPE || recent.type == CallLog.Calls.REJECTED_TYPE
    val (typeIcon, typeColor) = when (recent.type) {
        CallLog.Calls.OUTGOING_TYPE -> CarIcons.Outgoing to colors.text2
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CarIcons.Missed to CallRed
        else -> CarIcons.Incoming to colors.text2
    }
    val time = DateUtils.getRelativeTimeSpanString(recent.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    val detail = listOfNotNull(recent.numberLabel ?: recent.number.takeIf { recent.name != null }, time).joinToString(" · ")
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .carClickable(shape, stringResource(R.string.phone_call_name, title), onClick = onClick)
            .background(colors.surface2, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(recent.name, recent.photo, 60.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarLabel(title, 23.sp, if (missed) CallRed else colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.Bold)
                if (recent.count > 1) CarLabel(stringResource(R.string.phone_count, recent.count), 21.sp, colors.text2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarIcon(typeIcon, typeColor, Modifier.size(20.dp))
                CarLabel(detail, 19.sp, colors.text2)
            }
        }
        CarIcon(CarIcons.CallAnswer, CallGreen, Modifier.size(30.dp))
    }
}

@Composable
private fun FavoritesGrid(settings: LauncherSettings, onPick: (Contact) -> Unit) {
    val context = LocalContext.current
    val order by settings.nameOrder.collectAsStateWithLifecycle()
    val favorites by produceState<List<Contact>?>(null, order) {
        value = PhoneBook.contacts(context, order, starredOnly = true).also { EventLog.log("Phone: ${it.size} favorites") }
    }
    val list = favorites
    when {
        list == null -> PanelMessage(stringResource(R.string.music_loading))
        list.isEmpty() -> PanelMessage(stringResource(if (PhoneBook.canRead(context)) R.string.phone_no_favorites else R.string.phone_no_access))
        else -> BoxWithConstraints {
            // Big tiles: 2 to 5 per row depending on the width
            val columns = (maxWidth / 220.dp).toInt().coerceIn(2, 5)
            LazyVerticalGrid(
                GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(list, key = { it.id }) { contact -> FavoriteTile(contact) { onPick(contact) } }
            }
        }
    }
}

@Composable
private fun FavoriteTile(contact: Contact, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .carClickable(shape, stringResource(R.string.phone_call_name, contact.name), onClick = onClick)
            .background(colors.surface2, shape)
            .padding(vertical = 18.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(contact.name, contact.photoLarge ?: contact.photo, 96.dp)
        CarLabel(contact.name, 21.sp, colors.text, weight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
        contact.company?.let { CarLabel(it, 17.sp, colors.text2, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun ContactsList(settings: LauncherSettings, onPick: (Contact) -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val order by settings.nameOrder.collectAsStateWithLifecycle()
    val contacts by produceState<List<Contact>?>(null, order) {
        value = PhoneBook.contacts(context, order).also { EventLog.log("Phone: ${it.size} contacts") }
    }
    val list = contacts
    when {
        list == null -> PanelMessage(stringResource(R.string.music_loading))
        list.isEmpty() -> PanelMessage(stringResource(if (PhoneBook.canRead(context)) R.string.phone_no_contacts else R.string.phone_no_access))
        else -> {
            // Lazy-list index of the first contact of each letter (right after the letter's header)
            val firstOfLetter = remember(list) {
                val map = LinkedHashMap<Char, Int>()
                var index = 0
                var letter: Char? = null
                for (contact in list) {
                    val initial = indexLetter(contact.name)
                    if (initial != letter) {
                        letter = initial
                        index++
                        map[initial] = index
                    }
                    index++
                }
                map
            }
            val jump = rememberAlphabetJump()
            val listState = rememberLazyListState()
            AlphabetJumpEffect(jump, listState)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CarLabel(stringResource(R.string.phone_contacts_count, list.size), 20.sp, colors.text2, Modifier.weight(1f).padding(start = 8.dp))
                    if (firstOfLetter.size > 1 && !jump.open) AlphabetButton(jump)
                }
                if (jump.open) {
                    AlphabetPicker(firstOfLetter.keys.toList(), onPick = { jump.jumpTo(firstOfLetter.getValue(it)) }, onClose = { jump.open = false })
                } else {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        var letter: Char? = null
                        var index = 0
                        for (contact in list) {
                            val initial = indexLetter(contact.name)
                            if (initial != letter) {
                                letter = initial
                                index++
                                item(key = "letter-$initial") {
                                    CarLabel(initial.toString(), 22.sp, colors.focus, Modifier.padding(start = 18.dp, top = 6.dp), weight = FontWeight.ExtraBold)
                                }
                            }
                            val position = index++
                            item(key = contact.id) {
                                ContactRow(contact, jump.focusTarget(position)) { onPick(contact) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .carClickable(shape, stringResource(R.string.phone_call_name, contact.name), onClick = onClick)
            .background(colors.surface2, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(contact.name, contact.photo, 60.dp)
        Column(Modifier.weight(1f)) {
            CarLabel(contact.name, 23.sp, colors.text, weight = FontWeight.Bold)
            contact.company?.let { CarLabel(it, 19.sp, colors.text2) }
        }
        if (contact.starred) CarIcon(CarIcons.Star, colors.text2, Modifier.size(24.dp))
    }
}

/** A contact's numbers. With a single number the call starts right away. */
@Composable
private fun NumberPicker(contact: Contact, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val numbers by produceState<List<PhoneNumber>?>(null, contact.id) { value = PhoneBook.numbers(context, contact.id) }
    val list = numbers
    LaunchedEffect(list) {
        if (list != null && list.size == 1) {
            onClose()
            Calls.place(context, list.first().number)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            // Back closes the picker before leaving the section
            .onKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ListRowBack(onClose)
            Avatar(contact.name, contact.photoLarge ?: contact.photo, 64.dp)
            Column {
                CarLabel(contact.name, 26.sp, colors.text, weight = FontWeight.ExtraBold)
                CarLabel(listOfNotNull(contact.company, stringResource(R.string.phone_pick_number)).joinToString(" · "), 19.sp, colors.text2)
            }
        }
        when {
            list == null -> PanelMessage(stringResource(R.string.music_loading))
            list.size > 1 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.number }) { number ->
                    ListRow(
                        icon = null,
                        fallback = CarIcons.CallAnswer,
                        title = number.label,
                        subtitle = number.number,
                        highlighted = number.primary,
                        roundIcon = true,
                    ) {
                        onClose()
                        Calls.place(context, number.number)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRowBack(onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        Modifier
            .carClickable(CircleShape, stringResource(R.string.music_back), onClick = onClick)
            .background(colors.surface2, CircleShape)
            .size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(CarIcons.Back, colors.text, Modifier.size(26.dp))
    }
}

/** Number entry: the number on top, the keys, then the call button at the bottom. */
@Composable
internal fun Keypad(onCall: ((String) -> Unit)?, onDigit: (Char) -> Unit = {}) {
    val colors = LocalCarColors.current
    var number by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarLabel(number.ifEmpty { " " }, 40.sp, colors.text, Modifier.weight(1f, fill = false), weight = FontWeight.Bold, tabularNumbers = true)
                if (number.isNotEmpty()) {
                    Box(
                        Modifier
                            .carClickable(CircleShape, stringResource(R.string.phone_delete_digit)) { number = number.dropLast(1) }
                            .size(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CarIcon(CarIcons.Backspace, colors.text2, Modifier.size(28.dp))
                    }
                }
            }
            KeyGrid(Modifier.weight(1f)) { digit ->
                number += digit
                onDigit(digit)
            }
            if (onCall != null) {
                RoundButton(CarIcons.CallAnswer, stringResource(R.string.phone_call), CallGreen, Color.White, 84.dp) {
                    if (number.isNotBlank()) onCall(number)
                }
            }
        }
    }
}

@Composable
private fun KeyGrid(modifier: Modifier, onKey: (Char) -> Unit) {
    val colors = LocalCarColors.current
    val rows = listOf("123", "456", "789", "*0#")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (key in row) {
                    val shape = RoundedCornerShape(18.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .carClickable(shape, key.toString()) { onKey(key) }
                            .background(colors.surface2, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        CarLabel(key.toString(), 32.sp, colors.text, weight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Round photo, or the initials on a color picked from the name. */
@Composable
internal fun Avatar(name: String?, photo: Uri?, size: Dp) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val image by produceState<ImageBitmap?>(null, photo) { value = photo?.let { PhoneBook.photo(context, it) } }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
        return
    }
    val initials = name?.split(' ')?.filter { it.isNotBlank() }?.take(2)?.joinToString("") { it.take(1).uppercase() }
    val tint = AvatarColors[((name?.hashCode() ?: 0) and Int.MAX_VALUE) % AvatarColors.size]
    Box(Modifier.size(size).background(if (initials.isNullOrEmpty()) colors.surface else tint, CircleShape), contentAlignment = Alignment.Center) {
        if (initials.isNullOrEmpty()) {
            CarIcon(CarIcons.Artist, colors.text2, Modifier.size(size * 0.45f))
        } else {
            CarLabel(initials, (size.value * 0.36f).sp, Color.White, weight = FontWeight.Bold)
        }
    }
}

private val AvatarColors = listOf(
    Color(0xFF3B82C4), Color(0xFF7C5CC4), Color(0xFFC4568A), Color(0xFFD9822B),
    Color(0xFF2E9E8F), Color(0xFF5C8A2E), Color(0xFF8A5C3B), Color(0xFF4A5DBA),
)

@Composable
internal fun RoundButton(
    icon: ImageVector,
    label: String,
    background: Color,
    content: Color,
    size: Dp,
    showLabel: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .carClickable(CircleShape, label, onClick = onClick)
                .background(background, CircleShape)
                .size(size),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(icon, content, Modifier.size(size * 0.4f))
        }
        if (showLabel) CarLabel(label, 19.sp, colors.text2, weight = FontWeight.Bold)
    }
}
