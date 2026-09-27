package com.mizanora.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Gemini API key (and a couple of small settings) encrypted on
 * the device, using Android's own Keystore-backed master key. This never
 * leaves the phone and is never sent anywhere except as part of the normal
 * HTTPS call to Google's Gemini endpoint when Mizanora is actually working.
 */
object SecureConfig {

    private const val TAG = "MizanoraSecureConfig"
    private const val PREFS_NAME = "mizanora_secure_prefs"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_WATCH_INTERVAL_SEC = "watch_interval_sec"
    private const val KEY_MAX_STEPS = "max_steps"

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Extremely unlikely (Keystore unavailable), but never crash the
            // app over this — fall back to normal (unencrypted) prefs.
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back", e)
            context.applicationContext.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getApiKey(context: Context): String {
        val saved = prefs(context).getString(KEY_API_KEY, "") ?: ""
        return saved.ifBlank { BuildConfig.GEMINI_API_KEY }
    }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    /** How often (seconds) continuous "watch" mode takes a look, default 15s. */
    fun getWatchIntervalSec(context: Context): Int =
        prefs(context).getInt(KEY_WATCH_INTERVAL_SEC, 15)

    fun setWatchIntervalSec(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_WATCH_INTERVAL_SEC, seconds.coerceIn(5, 300)).apply()
    }

    /** Max steps Mizanora will take on one task before stopping itself, default 40. */
    fun getMaxSteps(context: Context): Int =
        prefs(context).getInt(KEY_MAX_STEPS, 40)

    fun setMaxSteps(context: Context, steps: Int) {
        prefs(context).edit().putInt(KEY_MAX_STEPS, steps.coerceIn(5, 200)).apply()
    }
}
