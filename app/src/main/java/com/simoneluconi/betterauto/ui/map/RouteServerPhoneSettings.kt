package com.simoneluconi.betterauto.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.PillButton
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.map.nav.RouteServer
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.smarthome.PhoneTextField
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.launch

/** How the last "use this server" went. */
private sealed interface ServerCheck {
    data object Invalid : ServerCheck
    data object Checking : ServerCheck
    data class Saved(val version: String) : ServerCheck
    /** Nothing like Valhalla answered at [base]: kept only if the driver says so. */
    data class NoAnswer(val base: String) : ServerCheck
}

/**
 * Phone-only, inside its settings section: which Valhalla server the routes come from. An address typed is used
 * once it answers like Valhalla (or the driver insists); the demo server is always one tap away.
 */
@Composable
fun RouteServerPhoneSettings() {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val settings = remember { LauncherSettings.get(context) }
    val server by settings.routeServer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf(server.orEmpty()) }
    var check by remember { mutableStateOf<ServerCheck?>(null) }

    fun use(base: String) {
        settings.setRouteServer(base)
        EventLog.log("Navigation: own route server set")
    }

    CarLabel(
        server?.let { stringResource(R.string.route_server_in_use, RouteServer.host(it)) } ?: stringResource(R.string.route_server_demo_in_use),
        15.sp, colors.text, weight = FontWeight.Bold, maxLines = 2,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CarLabel(stringResource(R.string.route_server_address), 15.sp, colors.text2, weight = FontWeight.Bold)
        PhoneTextField(typed, { typed = it; check = null }, stringResource(R.string.route_server_hint))
        when (val c = check) {
            ServerCheck.Invalid -> CarLabel(stringResource(R.string.route_server_invalid), 14.sp, colors.text, maxLines = 2)
            ServerCheck.Checking -> CarLabel(stringResource(R.string.route_server_checking), 14.sp, colors.text2)
            is ServerCheck.Saved -> CarLabel(stringResource(R.string.route_server_saved, c.version), 14.sp, colors.text, maxLines = 2)
            is ServerCheck.NoAnswer -> {
                CarLabel(stringResource(R.string.route_server_no_answer), 14.sp, colors.text, maxLines = 3)
                CarLabel(
                    stringResource(R.string.route_server_use_anyway), 15.sp, colors.focus,
                    Modifier.clickable { use(c.base); check = null }.padding(vertical = 4.dp), weight = FontWeight.Bold,
                )
            }
            null -> CarLabel(stringResource(R.string.route_server_note), 14.sp, colors.text2, maxLines = 4)
        }
    }
    PillButton(stringResource(R.string.route_server_use), primary = true) {
        if (check == ServerCheck.Checking) return@PillButton
        val base = RouteServer.normalize(typed)
        if (base == null) {
            check = ServerCheck.Invalid
            return@PillButton
        }
        typed = base
        check = ServerCheck.Checking
        scope.launch {
            val version = RouteServer.check(base)
            check = if (version != null) ServerCheck.Saved(version).also { use(base) } else ServerCheck.NoAnswer(base)
        }
    }
    // No dead end: back to the server that needs nothing set up
    if (server != null) {
        PillButton(stringResource(R.string.route_server_back_to_demo), primary = false) {
            settings.setRouteServer(null)
            typed = ""
            check = null
            EventLog.log("Navigation: back to the demo route server")
        }
    }
}

/** Demo or the own server's host, for the settings home. */
@Composable
fun routeServerSummary(): String {
    val context = LocalContext.current
    val server by remember { LauncherSettings.get(context) }.routeServer.collectAsStateWithLifecycle()
    return stringResource(
        R.string.settings_option, stringResource(R.string.route_server_short),
        server?.let { RouteServer.host(it) } ?: stringResource(R.string.route_server_demo),
    )
}
