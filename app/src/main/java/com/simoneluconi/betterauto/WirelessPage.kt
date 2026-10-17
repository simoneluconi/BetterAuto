package com.simoneluconi.betterauto

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.audio.ShizukuAudio
import com.simoneluconi.betterauto.transport.wireless.WirelessAutoConnect
import com.simoneluconi.betterauto.transport.wireless.WirelessConnector
import com.simoneluconi.betterauto.transport.wireless.WirelessState
import com.simoneluconi.betterauto.transport.wireless.WirelessStep
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.verticalScrollWithBar
import kotlin.concurrent.thread

/** A paired Bluetooth device, and whether it offers wireless Android Auto. */
private class Paired(val address: String, val name: String, val wireless: Boolean)

/** Home card to the wireless page: the head unit or dongle picked, if any. */
@Composable
internal fun WirelessCard(onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val saved = remember { WirelessConnector.savedDevice(context)?.let { pairedName(context, it) } }
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
            CarIcon(CarIcons.Wireless, colors.text2, Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(stringResource(R.string.wireless_title), 18.sp, colors.text, weight = FontWeight.Bold)
            CarLabel(saved ?: stringResource(R.string.wireless_card), 15.sp, colors.text2, maxLines = 2)
        }
        CarIcon(CarIcons.Chevron, colors.text2, Modifier.size(22.dp))
    }
}

/**
 * Wireless Android Auto: pick the paired head unit or dongle, allow what it needs, connect. The cable
 * keeps working as before and wins when plugged in.
 */
@Composable
internal fun WirelessPage(onBack: () -> Unit) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val missing = remember(refresh) { WirelessConnector.missingPermissions(context) }
    val devices = remember(refresh, missing.isEmpty()) { if (missing.isEmpty()) paired(context) else emptyList() }
    var selected by remember { mutableStateOf(WirelessConnector.savedDevice(context)) }
    var showAll by remember { mutableStateOf(false) }
    val status by WirelessConnector.status.collectAsStateWithLifecycle()
    // Only those offering wireless Android Auto, unless asked: a phone has many paired devices.
    // The others stay one tap away, in case the phone has not read a device's services yet.
    val offering = devices.filter { it.wireless || it.address == selected }
    val shown = if (showAll || offering.isEmpty()) devices.sortedByDescending { it.wireless } else offering

    Column(Modifier.fillMaxSize().background(colors.bg).safeDrawingPadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScrollWithBar()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    CarIcon(CarIcons.Back, colors.text, Modifier.size(24.dp))
                }
                CarLabel(stringResource(R.string.wireless_title), 26.sp, colors.text, weight = FontWeight.ExtraBold)
            }
            CarLabel(stringResource(R.string.wireless_hint), 15.sp, colors.text2, Modifier.padding(horizontal = 4.dp), maxLines = 10, lineHeight = 21.sp)

            if (missing.isNotEmpty()) {
                Card {
                    CarLabel(stringResource(R.string.wireless_permissions), 15.sp, colors.text2, maxLines = 6, lineHeight = 21.sp)
                    PillButton(stringResource(R.string.action_allow), primary = true) { ask.launch(missing.toTypedArray()) }
                }
                return@Column
            }

            CarLabel(stringResource(R.string.wireless_pick), 19.sp, colors.text, Modifier.padding(start = 4.dp, top = 6.dp), weight = FontWeight.Bold)
            if (devices.isEmpty()) {
                Card { CarLabel(stringResource(R.string.wireless_none_paired), 15.sp, colors.text2, maxLines = 4) }
            }
            for (device in shown) {
                DeviceRow(device, chosen = device.address == selected) {
                    selected = device.address
                    WirelessConnector.saveDevice(context, device.address)
                }
            }
            val hidden = devices.size - shown.size
            if (hidden > 0) {
                PillButton(stringResource(R.string.wireless_show_all, hidden), primary = false) { showAll = true }
            }
            if (WirelessAutoConnect.supported()) selected?.let { AutoConnectCard(it) }
            if (missing.isEmpty()) DualWifiCard()
        }

        // Fixed at the bottom of the screen, however long the list: what is happening, and the button
        val address = selected
        if (missing.isEmpty() && address != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusLine(status)
                if (status is WirelessState.Connecting || status == WirelessState.Connected) {
                    val label = if (status == WirelessState.Connected) R.string.wireless_disconnect else R.string.wireless_cancel
                    PillButton(stringResource(label), primary = false, modifier = Modifier.fillMaxWidth()) {
                        context.startService(Intent(context, AapService::class.java).setAction(AapService.ACTION_CANCEL_WIRELESS))
                    }
                } else {
                    PillButton(stringResource(R.string.wireless_connect), primary = true, modifier = Modifier.fillMaxWidth()) {
                        context.startForegroundService(
                            Intent(context, AapService::class.java).setAction(AapService.ACTION_CONNECT_WIRELESS)
                                .putExtra(AapService.EXTRA_DEVICE, address)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Auto-connect for the picked device, on by default: Android's dialog to associate it comes up once, then
 * the system tells BetterAuto whenever the device connects over Bluetooth. Picking another device moves it;
 * declining the dialog turns it off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoConnectCard(address: String) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    var on by remember { mutableStateOf(WirelessAutoConnect.enabled(context)) }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            on = WirelessAutoConnect.finish(context, address)
        } else {
            WirelessAutoConnect.declined(context)
            on = false
        }
    }
    fun turnOn() = WirelessAutoConnect.enable(
        context, address,
        onDialog = { dialog.launch(IntentSenderRequest.Builder(it).build()) },
        onDone = { on = it },
    )
    // On but not set up for this device yet (first time, or another one was picked): set it up
    LaunchedEffect(address) {
        val watched = WirelessAutoConnect.watched(context)
        if (on && !watched.equals(address, ignoreCase = true)) {
            if (watched != null) {
                WirelessAutoConnect.disable(context, watched)
            }
            turnOn()
        }
    }
    Card {
        CarLabel(stringResource(R.string.wireless_auto_title), 18.sp, colors.text, weight = FontWeight.Bold)
        CarLabel(stringResource(R.string.wireless_auto_hint), 15.sp, colors.text2, maxLines = 3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.option_off), selected = !on) {
                if (on) WirelessAutoConnect.disable(context, address)
                on = false
            }
            Chip(stringResource(R.string.option_on), selected = on) { if (!on) turnOn() }
        }
    }
}

/**
 * Dual WiFi: the phone keeps its own WiFi (and internet, adb) while on the head unit's. A setting only the
 * shell user can change, so it needs Shizuku: without it the card is there but cannot be switched on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DualWifiCard() {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val shizukuChanges by ShizukuAudio.changes.collectAsStateWithLifecycle()
    val available = remember(shizukuChanges) { ShizukuAudio.state(context) != ShizukuAudio.State.NOT_INSTALLED }
    var on by remember { mutableStateOf(WirelessConnector.dualWifi(context)) }
    fun set(value: Boolean) {
        if (value == on) return
        on = value
        WirelessConnector.setDualWifi(context, value)
        // Now too, not only at the next connection: the phone may already be on the head unit's WiFi
        thread(name = "dual-wifi") { ShizukuAudio.setDualWifi(context, value) }
    }
    Card {
        CarLabel(stringResource(R.string.wireless_dual_title), 18.sp, colors.text, weight = FontWeight.Bold)
        CarLabel(stringResource(R.string.wireless_dual_hint), 15.sp, colors.text2, maxLines = 3)
        if (!available) CarLabel(stringResource(R.string.wireless_dual_needs_shizuku), 15.sp, colors.text2)
        FlowRow(
            Modifier.alpha(if (available) 1f else 0.4f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(stringResource(R.string.option_off), selected = !on) { if (available) set(false) }
            Chip(stringResource(R.string.option_on), selected = on) { if (available) set(true) }
        }
    }
}

/** One line on the connection in progress, its end, or why it failed. */
@Composable
private fun StatusLine(status: WirelessState) {
    val colors = LocalCarColors.current
    when (status) {
        WirelessState.Idle -> Unit
        is WirelessState.Connecting -> CarLabel(stringResource(stepLabel(status.step)), 16.sp, colors.text, weight = FontWeight.Bold)
        WirelessState.Connected -> CarLabel(stringResource(R.string.wireless_connected), 16.sp, colors.text, weight = FontWeight.Bold)
        is WirelessState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CarLabel(stringResource(failureLabel(status.step)), 15.sp, colors.text, weight = FontWeight.Bold, maxLines = 4, lineHeight = 20.sp)
            if (status.detail.isNotBlank()) CarLabel(status.detail, 13.sp, colors.text2, maxLines = 3)
        }
    }
}

private fun stepLabel(step: WirelessStep) = when (step) {
    WirelessStep.BLUETOOTH -> R.string.wireless_step_bluetooth
    WirelessStep.WIFI_DETAILS -> R.string.wireless_step_wifi_details
    WirelessStep.WIFI_JOIN -> R.string.wireless_step_wifi_join
    WirelessStep.TCP -> R.string.wireless_step_tcp
}

private fun failureLabel(step: WirelessStep) = when (step) {
    WirelessStep.BLUETOOTH -> R.string.wireless_failed_bluetooth
    WirelessStep.WIFI_DETAILS -> R.string.wireless_failed_wifi_details
    WirelessStep.WIFI_JOIN -> R.string.wireless_failed_wifi_join
    WirelessStep.TCP -> R.string.wireless_failed_tcp
}

@Composable
private fun DeviceRow(device: Paired, chosen: Boolean, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (chosen) colors.focus.copy(alpha = 0.16f) else colors.surface)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(if (chosen) colors.focus else colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CarIcon(if (chosen) CarIcons.Check else CarIcons.Wireless, if (chosen) Color.White else colors.text2, Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CarLabel(device.name, 17.sp, colors.text, weight = FontWeight.Bold, maxLines = 2)
            CarLabel(
                stringResource(if (device.wireless) R.string.wireless_supported else R.string.wireless_unknown),
                14.sp, colors.text2, maxLines = 2,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = LocalCarColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@SuppressLint("MissingPermission") // only called once BLUETOOTH_CONNECT is granted
private fun paired(context: Context): List<Paired> {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet()).map { device ->
        Paired(device.address, runCatching { device.name }.getOrNull() ?: device.address, offersWireless(device))
    }
}

@SuppressLint("MissingPermission")
private fun offersWireless(device: BluetoothDevice) =
    runCatching { device.uuids.orEmpty().any { it.uuid == WirelessConnector.ANDROID_AUTO_WIRELESS } }.getOrDefault(false)

@SuppressLint("MissingPermission")
private fun pairedName(context: Context, address: String): String? = runCatching {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)?.name
}.getOrNull()
