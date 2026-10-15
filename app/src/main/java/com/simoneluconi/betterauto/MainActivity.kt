package com.simoneluconi.betterauto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.core.app.NotificationManagerCompat
import android.widget.ScrollView
import android.widget.TextView

/** Shows the connection status and the protocol log. */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val onLine: (String) -> Unit = { line -> runOnUiThread { appendLine(line) } }
    private val onStatus: (String?) -> Unit = { status -> runOnUiThread { showStatus(status) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        findViewById<Button>(R.id.preview).setOnClickListener {
            startActivity(Intent(this, CarPreviewActivity::class.java))
        }
        findViewById<Button>(R.id.notification_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onStart() {
        super.onStart()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        findViewById<Button>(R.id.notification_access).text =
            getString(if (granted) R.string.notification_access_granted else R.string.action_notification_access)
        logView.text = EventLog.snapshot().joinToString("\n")
        showStatus(EventLog.status)
        EventLog.addListeners(onLine, onStatus)
    }

    override fun onStop() {
        EventLog.removeListeners(onLine, onStatus)
        super.onStop()
    }

    private fun appendLine(line: String) {
        if (logView.text.isNotEmpty()) logView.append("\n")
        logView.append(line)
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showStatus(status: String?) {
        statusView.text = status?.let { getString(R.string.status_connected, it) } ?: getString(R.string.status_idle)
    }
}
