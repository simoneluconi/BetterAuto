package com.simoneluconi.betterauto.exlap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.Chip
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * The phone-only settings card for Exlap, laid out like the other settings sections: on/off chips, user and
 * password, and how the last session in the car went. The password is typed here, stored encrypted on
 * this phone and never shown again.
 */
@Composable
fun ExlapPhoneSettings() {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val exlap = remember { ExlapSettings.get(context) }
    val enabled by exlap.enabled.collectAsStateWithLifecycle()
    val savedUser by exlap.user.collectAsStateWithLifecycle()
    val hasPassword by exlap.hasPassword.collectAsStateWithLifecycle()
    val status by exlap.status.collectAsStateWithLifecycle()
    var user by remember(savedUser) { mutableStateOf(savedUser) }
    var password by remember { mutableStateOf("") }

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
        Setting(stringResource(R.string.exlap_enable)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.option_off), selected = !enabled) { exlap.setEnabled(false) }
                Chip(stringResource(R.string.option_on), selected = enabled) { exlap.setEnabled(true) }
            }
        }
        if (!enabled) return@Column
        Setting(stringResource(R.string.exlap_user)) { Field(user, secret = false) { user = it } }
        Setting(stringResource(if (hasPassword) R.string.exlap_password_saved else R.string.exlap_password)) {
            Field(password, secret = true) { password = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canSave = user.isNotBlank() && (password.isNotEmpty() || (hasPassword && user.trim() != savedUser))
            Chip(stringResource(R.string.exlap_save), selected = canSave) {
                if (!canSave) return@Chip
                if (password.isNotEmpty()) exlap.setCredentials(user.trim(), password) else exlap.renameUser(user.trim())
                password = ""
            }
            if (hasPassword) Chip(stringResource(R.string.exlap_forget), selected = false) {
                exlap.clearCredentials()
                password = ""
            }
        }
        status?.let { CarLabel(statusText(it), 15.sp, colors.text2, maxLines = 2) }
    }
}

/** A title over its control, as in the other settings cards. */
@Composable
private fun Setting(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CarLabel(title, 16.sp, LocalCarColors.current.text2, weight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Field(value: String, secret: Boolean, onChange: (String) -> Unit) {
    val colors = LocalCarColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.text, fontSize = 15.sp),
        cursorBrush = SolidColor(colors.text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(colors.surface2)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun statusText(status: ExlapStatus): String = when (status.state) {
    ExlapState.NO_CREDENTIALS -> stringResource(R.string.exlap_status_no_credentials)
    ExlapState.CONNECTING -> stringResource(R.string.exlap_status_connecting)
    ExlapState.LOGGED_IN -> stringResource(R.string.exlap_status_logged_in, status.objects)
    ExlapState.LOGIN_REFUSED -> stringResource(R.string.exlap_status_refused)
    ExlapState.NOT_ANSWERING -> stringResource(R.string.exlap_status_not_answering)
}
