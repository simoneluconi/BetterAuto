package com.simoneluconi.betterauto.exlap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.PhoneSwitchLine
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** Phone-only, inside its settings section (title and description drawn there): on/off and how the last car trip went. */
@Composable
fun ExlapPhoneSettings() {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val exlap = remember { ExlapSettings.get(context) }
    val enabled by exlap.enabled.collectAsStateWithLifecycle()
    val status by exlap.status.collectAsStateWithLifecycle()
    PhoneSwitchLine(stringResource(R.string.exlap_enable), stringResource(R.string.exlap_enable_desc), enabled) { exlap.setEnabled(!enabled) }
    status?.let { CarLabel(statusText(it), 15.sp, colors.text2, maxLines = 2) }
}

/** On or off, for the settings home. */
@Composable
fun exlapSummary(): String {
    val context = LocalContext.current
    val enabled by remember { ExlapSettings.get(context) }.enabled.collectAsStateWithLifecycle()
    return stringResource(R.string.settings_option, stringResource(R.string.exlap_short), stringResource(if (enabled) R.string.option_on else R.string.option_off))
}

@Composable
private fun statusText(status: ExlapStatus): String = when (status.state) {
    ExlapState.NO_CREDENTIALS -> stringResource(R.string.exlap_status_no_credentials)
    ExlapState.CONNECTING -> stringResource(R.string.exlap_status_connecting)
    ExlapState.LOGGED_IN -> stringResource(R.string.exlap_status_logged_in, status.objects)
    ExlapState.LOGIN_REFUSED -> stringResource(R.string.exlap_status_refused)
    ExlapState.NOT_ANSWERING -> stringResource(R.string.exlap_status_not_answering)
}
