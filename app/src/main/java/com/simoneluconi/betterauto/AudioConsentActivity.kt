package com.simoneluconi.betterauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.edit

/**
 * Asks for what car audio needs: the RECORD_AUDIO permission (playback capture is an AudioRecord)
 * and Android's capture consent, then hands the consent to [AapService].
 *
 * Android requires a fresh consent for every capture session, so without the ADB pre-approval
 * (appops PROJECT_MEDIA allow, which makes Android grant it silently) this shows up each time the car
 * connects. The first time, a short explanation comes before Android's own dialog, whose wording is
 * about recording the screen. Declining is fine: the service notification offers to try again.
 */
class AudioConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXPLAINED, false) || isPreApproved()) {
            requestAccess()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.audio_consent_title)
            .setMessage(R.string.audio_consent_message)
            .setPositiveButton(R.string.audio_consent_continue) { _, _ ->
                prefs.edit { putBoolean(KEY_EXPLAINED, true) }
                requestAccess()
            }
            .setNegativeButton(R.string.audio_consent_later) { _, _ -> declined() }
            .setOnCancelListener { declined() }
            .show()
    }

    private fun requestAccess() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askCapture()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    /** True when the ADB pre-approval is in place: Android will grant capture without asking, nothing to explain. */
    private fun isPreApproved(): Boolean = runCatching {
        getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(OPSTR_PROJECT_MEDIA, applicationInfo.uid, packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun declined() {
        EventLog.log("Car audio: not enabled now, audio stays on the phone")
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            askCapture()
        } else {
            EventLog.log("Car audio: microphone permission denied, audio stays on the phone")
            finish()
        }
    }

    private fun askCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Whole display, so the capture covers every app's audio rather than a single one
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Activity result API not needed for this one-shot consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, AapService::class.java)
                        .setAction(AapService.ACTION_AUDIO_CONSENT)
                        .putExtra(AapService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(AapService.EXTRA_RESULT_DATA, data)
                )
            } else {
                EventLog.log("Car audio: capture consent declined, audio stays on the phone")
            }
        }
        finish()
    }

    companion object {
        /** AppOpsManager.OPSTR_PROJECT_MEDIA, not public in the SDK. */
        const val OPSTR_PROJECT_MEDIA = "android:project_media"

        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_CAPTURE = 2
        private const val PREFS = "car_audio"
        private const val KEY_EXPLAINED = "consent_explained"
    }
}
