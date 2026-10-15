package com.simoneluconi.betterauto

import android.Manifest
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.simoneluconi.betterauto.audio.CarAudio
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.CarTheme
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** The app on the phone: connection status, a setup checklist, the car UI preview and the protocol log. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // If the app died while connected, the phone may still be muted: put the volume back now
        CarAudio.init(this)
        setContent {
            CarTheme(night = isSystemInDarkTheme(), album = null) {
                var page by rememberSaveable { mutableStateOf(PhonePage.HOME) }
                BackHandler(enabled = page != PhonePage.HOME) { page = PhonePage.HOME }
                when (page) {
                    PhonePage.HOME -> PhoneHome(onOpenSettings = { page = PhonePage.SETTINGS })
                    PhonePage.SETTINGS -> PhoneSettingsPage(onBack = { page = PhonePage.HOME })
                }
            }
        }
    }
}

private enum class PhonePage { HOME, SETTINGS }

private enum class StepState { DONE, TODO, OPTIONAL }

private class Step(
    val icon: ImageVector,
    val title: String,
    val text: String,
    val state: StepState,
    val action: String? = null,
    val onAction: () -> Unit = {},
    /** A command to run from a computer, for what an app cannot do by itself. */
    val command: String? = null,
)

@Composable
private fun PhoneHome(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    // Permissions change in other screens: re-check whenever we come back
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumes++ }

    val steps = remember(resumes) {
        listOf(
            notificationsStep(context) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
            mediaStep(context),
            audioStep(context) { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
            phoneStep(context) { askPhone.launch(PhonePermissions) },
            androidAutoStep(context),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CarLabel(stringResource(R.string.app_name), 32.sp, colors.text, weight = FontWeight.ExtraBold)
            CarLabel(stringResource(R.string.home_subtitle), 16.sp, colors.text2, maxLines = 2)
        }
        ConnectionCard()

        val ready = steps.count { it.state != StepState.TODO }
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
            CarLabel(stringResource(R.string.setup_title), 22.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
            CarLabel(stringResource(R.string.setup_progress, ready, steps.size), 15.sp, colors.text2)
        }
        steps.forEach { StepCard(it) }
        SettingsCard(onOpenSettings)

        PillButton(stringResource(R.string.action_preview), primary = true, modifier = Modifier.fillMaxWidth()) {
            context.startActivity(Intent(context, CarPreviewActivity::class.java))
        }
        LogCard()
    }
}

@Composable
private fun ConnectionCard() {
    val colors = LocalCarColors.current
    var headUnit by remember { mutableStateOf(EventLog.status) }
    DisposableEffect(Unit) {
        val onLine: (String) -> Unit = {}
        val onStatus: (String?) -> Unit = { headUnit = it }
        EventLog.addListeners(onLine, onStatus)
        headUnit = EventLog.status
        onDispose { EventLog.removeListeners(onLine, onStatus) }
    }
    val connected = headUnit != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(if (connected) colors.focus.copy(alpha = 0.16f) else colors.surface)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(56.dp).background(if (connected) colors.focus else colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(CarIcons.Car, if (connected) Color.White else colors.text2, Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(
                stringResource(if (connected) R.string.home_connected else R.string.home_not_connected),
                20.sp, colors.text, weight = FontWeight.Bold,
            )
            CarLabel(headUnit ?: stringResource(R.string.home_plug_in), 15.sp, colors.text2, maxLines = 2)
        }
    }
}

@Composable
private fun StepCard(step: Step) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
                CarIcon(step.icon, colors.text2, Modifier.size(22.dp))
            }
            CarLabel(step.title, 18.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold, maxLines = 2)
            StateBadge(step.state)
        }
        CarLabel(step.text, 15.sp, colors.text2, maxLines = 8, lineHeight = 21.sp)
        step.command?.let { command ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface2)
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    command,
                    Modifier.weight(1f),
                    style = TextStyle(color = colors.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                )
                PillButton(stringResource(R.string.action_copy), primary = false) { copy(context, command) }
            }
        }
        step.action?.let { PillButton(it, primary = step.state == StepState.TODO, onClick = step.onAction) }
    }
}

@Composable
private fun StateBadge(state: StepState) {
    val colors = LocalCarColors.current
    val (label, background, content) = when (state) {
        StepState.DONE -> Triple(R.string.step_ready, Color(0xFF2E9E5B), Color.White)
        StepState.TODO -> Triple(R.string.step_todo, colors.focus, Color.White)
        StepState.OPTIONAL -> Triple(R.string.step_optional, colors.surface2, colors.text2)
    }
    Row(
        Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state == StepState.DONE) CarIcon(CarIcons.Check, content, Modifier.size(16.dp))
        CarLabel(stringResource(label), 13.sp, content, weight = FontWeight.Bold)
    }
}

/** Opens the car screen settings page. */
@Composable
private fun SettingsCard(onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Settings, colors.text2, Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(stringResource(R.string.phone_settings_title), 18.sp, colors.text, weight = FontWeight.Bold)
            CarLabel(stringResource(R.string.phone_settings_card), 15.sp, colors.text2, maxLines = 2)
        }
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
    }
}

@Composable
private fun LogCard() {
    val colors = LocalCarColors.current
    var open by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    DisposableEffect(open) {
        if (!open) return@DisposableEffect onDispose { }
        lines.clear()
        lines.addAll(EventLog.snapshot().takeLast(MAX_LOG_LINES))
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val onLine: (String) -> Unit = { line ->
            main.post {
                lines.add(line)
                if (lines.size > MAX_LOG_LINES) lines.removeAt(0)
            }
        }
        val onStatus: (String?) -> Unit = {}
        EventLog.addListeners(onLine, onStatus)
        onDispose { EventLog.removeListeners(onLine, onStatus) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarLabel(stringResource(R.string.log_title), 18.sp, colors.text, Modifier.weight(1f), weight = FontWeight.Bold)
            CarLabel(stringResource(if (open) R.string.log_hide else R.string.log_show), 15.sp, colors.focus, weight = FontWeight.Bold)
        }
        if (open) {
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll)
                    .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
            ) {
                BasicText(
                    lines.joinToString("\n").ifEmpty { stringResource(R.string.log_empty) },
                    style = TextStyle(color = colors.text2, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp),
                )
            }
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Box(
        modifier
            .widthIn(min = 88.dp)
            .clip(RoundedCornerShape(50))
            .background(if (primary) colors.focus else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CarLabel(label, 15.sp, if (primary) Color.White else colors.text, weight = FontWeight.Bold)
    }
}

private const val MAX_LOG_LINES = 300
private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

private fun notificationsStep(context: Context, request: () -> Unit): Step {
    val granted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return Step(
        icon = CarIcons.Bell,
        title = context.getString(R.string.step_notifications_title),
        text = context.getString(R.string.step_notifications_text),
        state = if (granted) StepState.DONE else StepState.TODO,
        action = if (granted) null else context.getString(R.string.action_allow),
        onAction = request,
    )
}

private fun mediaStep(context: Context): Step {
    val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    return Step(
        icon = CarIcons.Music,
        title = context.getString(R.string.step_media_title),
        text = context.getString(R.string.step_media_text),
        state = if (granted) StepState.DONE else StepState.TODO,
        action = if (granted) null else context.getString(R.string.action_open_settings),
        onAction = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
    )
}

private fun audioStep(context: Context, request: () -> Unit): Step {
    val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val automatic = runCatching {
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            AudioConsentActivity.OPSTR_PROJECT_MEDIA, context.applicationInfo.uid, context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    val title = context.getString(R.string.step_audio_title)
    return when {
        !microphone -> Step(
            CarIcons.Speaker, title, context.getString(R.string.step_audio_text_todo), StepState.TODO,
            action = context.getString(R.string.action_allow), onAction = request,
        )
        automatic -> Step(CarIcons.Speaker, title, context.getString(R.string.step_audio_text_done), StepState.DONE)
        else -> Step(
            CarIcons.Speaker, title, context.getString(R.string.step_audio_text_ask), StepState.OPTIONAL,
            command = "adb shell appops set ${context.packageName} PROJECT_MEDIA allow",
        )
    }
}

private val PhonePermissions = arrayOf(
    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE,
)

private fun phoneStep(context: Context, request: () -> Unit): Step {
    val granted = PhonePermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    return Step(
        icon = CarIcons.Phone,
        title = context.getString(R.string.step_phone_title),
        text = context.getString(R.string.step_phone_text),
        state = if (granted) StepState.DONE else StepState.TODO,
        action = if (granted) null else context.getString(R.string.action_allow),
        onAction = request,
    )
}

private fun androidAutoStep(context: Context): Step {
    val enabled = runCatching { context.packageManager.getApplicationInfo(ANDROID_AUTO, 0).enabled }.getOrDefault(false)
    val title = context.getString(R.string.step_android_auto_title)
    return if (!enabled) {
        Step(CarIcons.Car, title, context.getString(R.string.step_android_auto_text_done), StepState.DONE)
    } else {
        Step(
            CarIcons.Car, title, context.getString(R.string.step_android_auto_text_todo), StepState.TODO,
            action = context.getString(R.string.action_open_settings),
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ANDROID_AUTO, null))
                )
            },
            command = "adb shell pm disable-user --user 0 $ANDROID_AUTO",
        )
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", text))
    // Android 13+ confirms copies itself
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
