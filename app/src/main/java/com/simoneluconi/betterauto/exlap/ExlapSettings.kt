package com.simoneluconi.betterauto.exlap

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How the last Exlap session with the car went, for the phone settings. */
enum class ExlapState { NO_CREDENTIALS, CONNECTING, LOGGED_IN, LOGIN_REFUSED, NOT_ANSWERING }

data class ExlapStatus(val state: ExlapState, val objects: Int = 0)

/**
 * Access to a VW car's Exlap server (vehicle data): off by default and set on the phone only.
 * The password is stored encrypted with a key kept in the Android Keystore, is never logged and never
 * leaves the phone (the app has no backup).
 */
class ExlapSettings private constructor(private val prefs: SharedPreferences) {
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    private val _user = MutableStateFlow(prefs.getString(KEY_USER, null).orEmpty())
    val user: StateFlow<String> = _user

    private val _hasPassword = MutableStateFlow(prefs.contains(KEY_PASSWORD))
    val hasPassword: StateFlow<Boolean> = _hasPassword

    /** The last session with a car, or null if there was none since the app started. */
    val status = MutableStateFlow<ExlapStatus?>(null)

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit { putBoolean(KEY_ENABLED, value) }
    }

    fun setCredentials(user: String, password: String) {
        prefs.edit {
            putString(KEY_USER, user)
            putString(KEY_PASSWORD, encrypt(password))
        }
        _user.value = user
        _hasPassword.value = true
    }

    /** A new user name for the password already saved. */
    fun renameUser(user: String) {
        prefs.edit { putString(KEY_USER, user) }
        _user.value = user
    }

    fun clearCredentials() {
        prefs.edit {
            remove(KEY_USER)
            remove(KEY_PASSWORD)
        }
        _user.value = ""
        _hasPassword.value = false
    }

    /** User and password for a session, when Exlap is on and both are set. */
    fun credentials(): Pair<String, String>? {
        if (!_enabled.value) return null
        val user = _user.value.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        return user to password
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    /** IV followed by the ciphertext, as Base64. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).toString(Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "exlap_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12

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
