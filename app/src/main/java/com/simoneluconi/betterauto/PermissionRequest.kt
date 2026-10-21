package com.simoneluconi.betterauto

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/**
 * Asks for [permissions][request]'s argument the usual way; once the system won't ask any more (denied
 * twice, or "Don't ask again"), opens BetterAuto's page in the system settings instead, where they can
 * still be given. So an "Allow" button never does nothing. [onDone] runs after the answer.
 */
@Composable
fun rememberPermissionRequest(onDone: () -> Unit = {}): (Array<String>) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    return request@{ permissions ->
        val missing = permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return@request onDone()
        val activity = context.findActivity()
        val asked = PermissionMemory.askedBefore(context, missing)
        if (asked && activity != null && missing.none { activity.shouldShowRequestPermissionRationale(it) }) {
            EventLog.log("Permissions: the system won't ask again, opening the app's settings")
            openAppSettings(context)
        } else {
            PermissionMemory.mark(context, missing)
            launcher.launch(missing.toTypedArray())
        }
    }
}

/** BetterAuto's page in the system settings (permissions, notifications, battery). */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Which permissions were asked once: only then a missing rationale means "won't ask again" rather than "never asked". */
private object PermissionMemory {
    private const val PREFS = "permissions"
    private const val KEY = "asked"

    fun askedBefore(context: Context, permissions: List<String>): Boolean {
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
        return permissions.all { it in asked }
    }

    fun mark(context: Context, permissions: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putStringSet(KEY, prefs.getStringSet(KEY, emptySet()).orEmpty() + permissions) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
