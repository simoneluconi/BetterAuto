package com.simoneluconi.betterauto.smarthome.homeassistant

import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.PillButton
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.smarthome.LinkState
import com.simoneluconi.betterauto.smarthome.SmartHomeAuth
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.smarthome.PhoneTextField
import com.simoneluconi.betterauto.ui.smarthome.ProviderBadge
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * Linking Home Assistant on the phone: the address it is reached at from anywhere (prefilled from the
 * clipboard when that holds a Home Assistant address), then its own login in the browser. Advanced:
 * a long-lived access token. Once linked: which home, and the address in use.
 */
@Composable
internal fun HaLinkCard(provider: HomeAssistantProvider) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val link by provider.link.collectAsStateWithLifecycle()
    val home by provider.homeName.collectAsStateWithLifecycle()
    val linking by provider.linking.collectAsStateWithLifecycle()
    val companion = remember { provider.companionInstalled(context) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProviderBadge(provider)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CarLabel(provider.name, 18.sp, colors.text, weight = FontWeight.Bold)
                CarLabel(statusText(link, home), 15.sp, colors.text2, maxLines = 2)
            }
        }
        (linking as? HaLinking.Done)?.let { done ->
            val text = when (val r = done.result) {
                is HaLinkResult.Linked -> r.switchedTo?.let { stringResource(R.string.ha_link_switched, Uri.parse(it).host ?: it) }
                HaLinkResult.Refused -> stringResource(R.string.ha_link_refused)
                HaLinkResult.Unreachable -> stringResource(R.string.ha_link_unreachable)
            }
            text?.let { CarLabel(it, 15.sp, colors.text, maxLines = 4) }
        }
        when {
            linking == HaLinking.Working -> CarLabel(stringResource(R.string.ha_link_working), 15.sp, colors.text2)
            link == LinkState.NOT_LINKED -> LinkForm(provider, companion)
            link == LinkState.AUTH_FAILED -> CarLabel(stringResource(R.string.ha_link_expired), 15.sp, colors.text2, maxLines = 3)
            else -> provider.url?.let { CarLabel(Uri.parse(it).host ?: it, 15.sp, colors.text2) }
        }
    }
}

@Composable
private fun LinkForm(provider: HomeAssistantProvider, companion: Boolean) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var fromClipboard by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    // An address on the clipboard, only if it answers like Home Assistant: nothing else is suggested
    LaunchedEffect(Unit) {
        if (url.isNotEmpty()) return@LaunchedEffect
        val clip = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        val candidate = HaAuth.clipboardUrl(clip) ?: return@LaunchedEffect
        if (provider.probe(candidate) && url.isEmpty()) {
            url = candidate
            fromClipboard = true
        }
    }

    CarLabel(stringResource(if (companion) R.string.ha_link_hint_app else R.string.ha_link_hint), 15.sp, colors.text2, maxLines = 5, lineHeight = 21.sp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CarLabel(stringResource(R.string.ha_link_url), 15.sp, colors.text2, weight = FontWeight.Bold)
        PhoneTextField(url, { url = it; invalid = false; fromClipboard = false }, stringResource(R.string.ha_link_url_hint))
        when {
            invalid -> CarLabel(stringResource(R.string.ha_link_url_invalid), 14.sp, colors.text, maxLines = 2)
            fromClipboard -> CarLabel(stringResource(R.string.ha_link_from_clipboard), 14.sp, colors.text2, maxLines = 2)
            else -> CarLabel(stringResource(R.string.ha_link_url_note), 14.sp, colors.text2, maxLines = 3)
        }
    }
    PillButton(stringResource(R.string.ha_link_sign_in), primary = true) {
        val base = HaAuth.normalizeUrl(url)
        if (base == null) {
            invalid = true
            return@PillButton
        }
        provider.clearLinking()
        SmartHomeAuth.openSignIn(context, provider.authorizeUrl(base))
    }
    CarLabel(
        stringResource(if (advanced) R.string.ha_link_advanced_hide else R.string.ha_link_advanced),
        15.sp, colors.focus, Modifier.clickable { advanced = !advanced }.padding(vertical = 4.dp), weight = FontWeight.Bold,
    )
    if (advanced) {
        CarLabel(stringResource(R.string.ha_link_token_hint), 14.sp, colors.text2, maxLines = 4)
        PhoneTextField(token, { token = it }, stringResource(R.string.ha_link_token), secret = true, keyboard = KeyboardType.Password)
        PillButton(stringResource(R.string.ha_link_token_connect), primary = false) {
            val base = HaAuth.normalizeUrl(url)
            if (base == null || token.isBlank()) {
                invalid = base == null
                return@PillButton
            }
            provider.startTokenLink(base, token)
            token = ""
        }
    }
}

@Composable
private fun statusText(link: LinkState, home: String?): String = when (link) {
    LinkState.NOT_LINKED -> stringResource(R.string.ha_status_not_linked)
    LinkState.AUTH_FAILED -> stringResource(R.string.ha_status_auth_failed)
    LinkState.OFFLINE -> stringResource(R.string.ha_status_offline)
    LinkState.CONNECTING -> stringResource(R.string.ha_status_connecting)
    else -> home?.let { stringResource(R.string.ha_status_linked_home, it) } ?: stringResource(R.string.ha_status_linked)
}
