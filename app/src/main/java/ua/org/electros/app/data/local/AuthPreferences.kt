package ua.org.electros.app.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores station credentials only inside Android Keystore-backed [EncryptedPrefsFactory].
 * Non-secret charging preferences live in [SettingsStore] (Jetpack DataStore).
 */
class AuthPreferences(context: Context) {

    private val prefs: SharedPreferences = EncryptedPrefsFactory.create(
        context = context,
        fileName = "auth_prefs",
        legacyPlaintextNames = listOf("auth_prefs_fallback")
    )

    var deviceSerial: String
        get() = prefs.getString("KEY_DEVICE_SERIAL", "").orEmpty()
        set(value) = prefs.edit().putString("KEY_DEVICE_SERIAL", value.trim()).apply()

    var internetPassword: String
        get() = prefs.getString("KEY_INTERNET_PASSWORD", "").orEmpty()
        set(value) = prefs.edit().putString("KEY_INTERNET_PASSWORD", value.trim()).apply()

    fun hasValidCredentials(): Boolean {
        return deviceSerial.isNotBlank() && internetPassword.isNotBlank()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove("KEY_DEVICE_SERIAL")
            .remove("KEY_INTERNET_PASSWORD")
            .apply()
    }

    internal fun legacyPrefsForMigration(): SharedPreferences = prefs

    companion object {
        internal val LEGACY_SETTINGS_KEYS = listOf(
            "KEY_KWH_LIMIT_ENABLED",
            "KEY_TARGET_KWH_LIMIT",
            "KEY_PREFERRED_AMPERAGE",
            "KEY_SCHEDULE_ENABLED",
            "KEY_SCHEDULE_START",
            "KEY_SCHEDULE_STOP",
            "KEY_AUTO_RESUME",
            "KEY_VOLTAGE_BALANCING_ENABLED",
            "KEY_VOLTAGE_LOW_THRESHOLD",
            "KEY_VOLTAGE_HIGH_THRESHOLD",
            "KEY_VOLTAGE_MAX_AMPERAGE",
            "KEY_VOLTAGE_MIN_AMPERAGE",
            "KEY_DURATION_TIMER_ENABLED",
            "KEY_TARGET_DURATION_HOURS"
        )
    }
}
