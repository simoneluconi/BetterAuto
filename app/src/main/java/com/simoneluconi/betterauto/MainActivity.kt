package com.simoneluconi.betterauto

import com.simoneluconi.betterauto.ui.verticalScrollWithBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.LaunchedEffect
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
import com.simoneluconi.betterauto.audio.ShizukuAudio
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.smarthome.SmartHomeCard
import com.simoneluconi.betterauto.settings.SettingsCategory
import com.simoneluconi.betterauto.ui.theme.CarTheme
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/** The app on the phone: connection status, a setup checklist, the car UI preview and the protocol log. */
class MainActivity : ComponentActivity() {
    /** A page asked for by whoever opened the app (the smart-home sign-in coming back). */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // If the app died while connected, the phone may still be muted: put the volume back now
        CarAudio.init(this)
        ShizukuAudio.init()
        setContent {
            CarTheme(night = isSystemInDarkTheme(), album = null) {
                var page by rememberSaveable { mutableStateOf(PhonePage.HOME) }
                val request by openRequest
                LaunchedEffect(request) {
                    if (request == OPEN_SMART_HOME) page = PhonePage.SMART_HOME
                    openRequest.value = null
                }
                // Kept here, above the pages: coming back from settings finds the home page where it was
                val homeScroll = rememberScrollState()
                BackHandler(enabled = page != PhonePage.HOME) { page = PhonePage.HOME }
                when (page) {
                    PhonePage.HOME -> PhoneHome(
                        scroll = homeScroll,
                        onOpenSetup = { page = PhonePage.SETUP },
                        onOpenWireless = { page = PhonePage.WIRELESS },
                        onOpenSettings = { page = PhonePage.SETTINGS },
                        onOpenSmartHome = { page = PhonePage.SMART_HOME },
                    )
                    PhonePage.SETUP -> SetupPage(onBack = { page = PhonePage.HOME })
                    PhonePage.WIRELESS -> WirelessPage(onBack = { page = PhonePage.HOME })
                    PhonePage.SETTINGS -> PhoneSettingsPage(onBack = { page = PhonePage.HOME })
                    // The home page's smart-home card is a shortcut to Settings › Smart home
                    PhonePage.SMART_HOME -> PhoneSettingsPage(onBack = { page = PhonePage.HOME }, start = SettingsCategory.SMART_HOME)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_SMART_HOME = "smart_home"
    }
}

private enum class PhonePage { HOME, SETUP, WIRELESS, SETTINGS, SMART_HOME }

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
    /** Once done: where to change or undo it (system settings, Shizuku...), so a done step is never a dead end. */
    val link: String? = null,
    val onLink: () -> Unit = {},
)

/** The setup checklist, re-checked whenever the app comes back to the front and when Shizuku changes. */
@Composable
private fun rememberSteps(): List<Step> {
    val context = LocalContext.current
    // Permissions change in other screens: re-check whenever we come back
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    // After "Don't ask again" these open the app's system settings instead
    val ask = rememberPermissionRequest { resumes++ }

    // Shizuku starting, stopping or answering the permission request also changes its step
    val shizukuChanges by ShizukuAudio.changes.collectAsStateWithLifecycle()
    val steps = remember(resumes, shizukuChanges) {
        listOf(
            notificationsStep(context) { ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
            mediaStep(context),
            audioStep(context) { ask(arrayOf(Manifest.permission.RECORD_AUDIO)) },
            phoneStep(context) { ask(PhonePermissions) },
            voiceStep(context),
            androidAutoStep(context),
            shizukuStep(context),
        )
    }
    return steps
}

@Composable
private fun PhoneHome(
    scroll: ScrollState,
    onOpenSetup: () -> Unit,
    onOpenWireless: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSmartHome: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val steps = rememberSteps()

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScrollWithBar(scroll)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CarLabel(stringResource(R.string.app_name), 32.sp, colors.text, weight = FontWeight.ExtraBold)
            CarLabel(stringResource(R.string.home_subtitle), 16.sp, colors.text2, maxLines = 2)
        }
        ConnectionCard()
        SetupCard(steps, onOpenSetup)
        WirelessCard(onOpenWireless)
        SettingsCard(onOpenSettings)
        SmartHomeCard(onOpenSmartHome)

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
        CarLabel(step.text, 15.sp, colors.text2, maxLines = 14, lineHeight = 21.sp)
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
        step.link?.let { link ->
            CarLabel(
                link, 15.sp, colors.focus,
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = step.onLink).padding(vertical = 6.dp, horizontal = 2.dp),
                weight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StateBadge(state: StepState) {
    val colors = LocalCarColors.current
    val (label, background, content) = when (state) {
        StepState.DONE -> Triple(R.string.step_ready, StepDoneGreen, Color.White)
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

/** The setup at a glance: a green check when nothing is left to do, otherwise how many steps are. */
@Composable
private fun SetupCard(steps: List<Step>, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val ready = steps.count { it.state != StepState.TODO }
    val todo = steps.size - ready
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
        Box(
            Modifier.size(44.dp).background(if (todo == 0) StepDoneGreen else colors.focus, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (todo == 0) {
                CarIcon(CarIcons.Check, Color.White, Modifier.size(22.dp))
            } else {
                CarLabel(todo.toString(), 18.sp, Color.White, weight = FontWeight.ExtraBold)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(stringResource(R.string.setup_title), 18.sp, colors.text, weight = FontWeight.Bold)
            CarLabel(
                if (todo == 0) stringResource(R.string.setup_all_ready) else stringResource(R.string.setup_todo, todo),
                15.sp, colors.text2, maxLines = 2,
            )
        }
        CarLabel(stringResource(R.string.setup_count, ready, steps.size), 15.sp, colors.text2, weight = FontWeight.Bold, tabularNumbers = true)
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
    }
}

/** Every setup step with its explanation and action; back returns home. */
@Composable
private fun SetupPage(onBack: () -> Unit) {
    val colors = LocalCarColors.current
    val steps = rememberSteps()
    val ready = steps.count { it.state != StepState.TODO }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScrollWithBar()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
            }
            CarLabel(stringResource(R.string.setup_title), 26.sp, colors.text, Modifier.weight(1f), weight = FontWeight.ExtraBold)
            CarLabel(stringResource(R.string.setup_progress, ready, steps.size), 15.sp, colors.text2)
        }
        steps.forEach { StepCard(it) }
    }
}

private val StepDoneGreen = Color(0xFF2E9E5B)

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
                    .verticalScrollWithBar(scroll)
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
internal fun PillButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
        link = if (granted) context.getString(R.string.step_change_in_settings) else null,
        onLink = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }.onFailure { openAppSettings(context) }
        },
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
        link = if (granted) context.getString(R.string.step_change_in_settings) else null,
        onLink = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
    )
}

private fun audioStep(context: Context, request: () -> Unit): Step {
    val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val automatic = runCatching {
        context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AudioConsentActivity.OPSTR_PROJECT_MEDIA, context.applicationInfo.uid, context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    val title = context.getString(R.string.step_audio_title)
    return when {
        !microphone -> Step(
            CarIcons.Speaker, title, context.getString(R.string.step_audio_text_todo), StepState.TODO,
            action = context.getString(R.string.action_allow), onAction = request,
        )
        ShizukuAudio.state(context) == ShizukuAudio.State.READY -> Step(
            CarIcons.Speaker, title, context.getString(R.string.step_audio_text_shizuku), StepState.DONE,
            link = context.getString(R.string.step_change_in_settings), onLink = { openAppSettings(context) },
        )
        automatic -> Step(
            CarIcons.Speaker, title, context.getString(R.string.step_audio_text_done), StepState.DONE,
            link = context.getString(R.string.step_change_in_settings), onLink = { openAppSettings(context) },
        )
        else -> Step(
            CarIcons.Speaker, title, context.getString(R.string.step_audio_text_ask), StepState.OPTIONAL,
            command = "adb shell appops set ${context.packageName} PROJECT_MEDIA allow",
        )
    }
}

private val PhonePermissions = arrayOf(
    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE,
    // The network type next to the signal (5G, 4G+...); same group as calls, so no extra question
    Manifest.permission.READ_PHONE_STATE,
)

/** Voice commands use the phone's own offline recognizer: there or not, nothing to grant. */
private fun voiceStep(context: Context): Step {
    val available = Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    return Step(
        icon = CarIcons.Mic,
        title = context.getString(R.string.step_voice_title),
        text = context.getString(if (available) R.string.step_voice_ready else R.string.step_voice_missing),
        state = if (available) StepState.DONE else StepState.OPTIONAL,
    )
}

private fun phoneStep(context: Context, request: () -> Unit): Step {
    val granted = PhonePermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    return Step(
        icon = CarIcons.Phone,
        title = context.getString(R.string.step_phone_title),
        text = context.getString(R.string.step_phone_text),
        state = if (granted) StepState.DONE else StepState.TODO,
        action = if (granted) null else context.getString(R.string.action_allow),
        onAction = request,
        link = if (granted) context.getString(R.string.step_change_in_settings) else null,
        onLink = { openAppSettings(context) },
    )
}

private fun androidAutoStep(context: Context): Step {
    val installed = runCatching { context.packageManager.getApplicationInfo(ANDROID_AUTO, 0) }.isSuccess
    val enabled = runCatching { context.packageManager.getApplicationInfo(ANDROID_AUTO, 0).enabled }.getOrDefault(false)
    val title = context.getString(R.string.step_android_auto_title)
    return if (!enabled) {
        // Its system page has the button to turn it back on
        Step(
            CarIcons.Car, title, context.getString(R.string.step_android_auto_text_done), StepState.DONE,
            link = if (installed) context.getString(R.string.step_android_auto_reenable) else null,
            onLink = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ANDROID_AUTO, null)))
                }
            },
        )
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

/** Optional: with Shizuku the sound is moved to the car like the official app does, without it it is copied. */
private fun shizukuStep(context: Context): Step {
    val title = context.getString(R.string.step_shizuku_title)
    // What it is for first, whatever its state, then what to do
    fun text(state: Int) = context.getString(R.string.step_shizuku_purpose) + "\n" + context.getString(state)
    return when (ShizukuAudio.state(context)) {
        ShizukuAudio.State.NOT_INSTALLED -> Step(
            CarIcons.Settings, title, text(R.string.step_shizuku_text_missing), StepState.OPTIONAL,
            action = context.getString(R.string.action_get_shizuku),
            onAction = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuAudio.PLAY_STORE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        ShizukuAudio.State.NOT_RUNNING -> Step(
            CarIcons.Settings, title, text(R.string.step_shizuku_text_stopped), StepState.OPTIONAL,
            action = context.getString(R.string.action_open_shizuku),
            onAction = {
                context.packageManager.getLaunchIntentForPackage(ShizukuAudio.PACKAGE)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
        ShizukuAudio.State.NOT_ALLOWED -> Step(
            CarIcons.Settings, title, text(R.string.step_shizuku_text_allow), StepState.OPTIONAL,
            action = context.getString(R.string.action_allow), onAction = ShizukuAudio::requestPermission,
        )
        ShizukuAudio.State.READY -> Step(
            CarIcons.Settings, title, text(R.string.step_shizuku_text_ready), StepState.DONE,
            link = context.getString(R.string.action_open_shizuku),
            onLink = {
                context.packageManager.getLaunchIntentForPackage(ShizukuAudio.PACKAGE)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", text))
    // Android 13+ confirms copies itself
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
