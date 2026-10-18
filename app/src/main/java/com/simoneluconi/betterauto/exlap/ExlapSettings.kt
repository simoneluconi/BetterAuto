package com.simoneluconi.betterauto.exlap

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ExlapState { NO_CREDENTIALS, CONNECTING, LOGGED_IN, LOGIN_REFUSED, NOT_ANSWERING }

data class ExlapStatus(val state: ExlapState, val objects: Int = 0)

class ExlapSettings private constructor(private val prefs: SharedPreferences) {
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled

    val status = MutableStateFlow<ExlapStatus?>(null)

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit { putBoolean(KEY_ENABLED, value) }
    }

    fun credentials(): Pair<String, String>? {
        if (!_enabled.value) return null
        return USER to PASSWORD
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val USER = "OPEN-FFFFC0"
        private const val PASSWORD = "0XFdYtgsi0S3Cd7TPJTEI6tMupH3WXuET2OXP5ClV6o="

        @Volatile
        var current: ExlapSettings? = null
            private set

        fun get(context: Context): ExlapSettings = current ?: synchronized(this) {
            current ?: ExlapSettings(
                context.applicationContext.getSharedPreferences("exlap", Context.MODE_PRIVATE)
            ).also { current = it }
        }
    }
}
