package com.simoneluconi.betterauto.exlap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.Chip
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

@Composable
fun ExlapPhoneSettings() {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val exlap = remember { ExlapSettings.get(context) }
    val enabled by exlap.enabled.collectAsStateWithLifecycle()
    val status by exlap.status.collectAsStateWithLifecycle()

    CarLabel(stringResource(R.string.exlap_title), 19.sp, colors.text, Modifier.padding(start = 4.dp, top = 6.dp), weight = FontWeight.Bold)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        CarLabel(stringResource(R.string.exlap_hint), 15.sp, colors.text2, maxLines = 6)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CarLabel(stringResource(R.string.exlap_enable), 16.sp, colors.text2, weight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.option_off), selected = !enabled) { exlap.setEnabled(false) }
                Chip(stringResource(R.string.option_on), selected = enabled) { exlap.setEnabled(true) }
            }
        }
        status?.let { CarLabel(statusText(it), 15.sp, colors.text2, maxLines = 2) }
    }
}

@Composable
private fun statusText(status: ExlapStatus): String = when (status.state) {
    ExlapState.NO_CREDENTIALS -> stringResource(R.string.exlap_status_no_credentials)
    ExlapState.CONNECTING -> stringResource(R.string.exlap_status_connecting)
    ExlapState.LOGGED_IN -> stringResource(R.string.exlap_status_logged_in, status.objects)
    ExlapState.LOGIN_REFUSED -> stringResource(R.string.exlap_status_refused)
    ExlapState.NOT_ANSWERING -> stringResource(R.string.exlap_status_not_answering)
}
