package ua.org.electros.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthPreferences(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "auth_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("auth_prefs_fallback", Context.MODE_PRIVATE)
    }

    var deviceSerial: String
        get() = prefs.getString("KEY_DEVICE_SERIAL", "").orEmpty()
        set(value) = prefs.edit().putString("KEY_DEVICE_SERIAL", value.trim()).apply()

    var internetPassword: String
        get() = prefs.getString("KEY_INTERNET_PASSWORD", "").orEmpty()
        set(value) = prefs.edit().putString("KEY_INTERNET_PASSWORD", value.trim()).apply()

    var isKwhLimitEnabled: Boolean
        get() = prefs.getBoolean("KEY_KWH_LIMIT_ENABLED", false)
        set(value) = prefs.edit().putBoolean("KEY_KWH_LIMIT_ENABLED", value).apply()

    var targetKwhLimit: Double
        get() = prefs.getFloat("KEY_TARGET_KWH_LIMIT", 15.0f).toDouble()
        set(value) = prefs.edit().putFloat("KEY_TARGET_KWH_LIMIT", value.toFloat()).apply()

    var preferredAmperage: Int
        get() = prefs.getInt("KEY_PREFERRED_AMPERAGE", 16)
        set(value) = prefs.edit().putInt("KEY_PREFERRED_AMPERAGE", value).apply()

    var isScheduleEnabled: Boolean
        get() = prefs.getBoolean("KEY_SCHEDULE_ENABLED", false)
        set(value) = prefs.edit().putBoolean("KEY_SCHEDULE_ENABLED", value).apply()

    var scheduleStartTime: String
        get() = prefs.getString("KEY_SCHEDULE_START", "23:00") ?: "23:00"
        set(value) = prefs.edit().putString("KEY_SCHEDULE_START", value).apply()

    var scheduleStopTime: String
        get() = prefs.getString("KEY_SCHEDULE_STOP", "07:00") ?: "07:00"
        set(value) = prefs.edit().putString("KEY_SCHEDULE_STOP", value).apply()

    var autoResumeAfterOutage: Boolean
        get() = prefs.getBoolean("KEY_AUTO_RESUME", true)
        set(value) = prefs.edit().putBoolean("KEY_AUTO_RESUME", value).apply()

    var stopAtTariffBoundary: Boolean
        get() = prefs.getBoolean("KEY_STOP_AT_TARIFF", false)
        set(value) = prefs.edit().putBoolean("KEY_STOP_AT_TARIFF", value).apply()

    var isVoltageBalancingEnabled: Boolean
        get() = prefs.getBoolean("KEY_VOLTAGE_BALANCING_ENABLED", true)
        set(value) = prefs.edit().putBoolean("KEY_VOLTAGE_BALANCING_ENABLED", value).apply()

    var voltageLowThreshold: Int
        get() = prefs.getInt("KEY_VOLTAGE_LOW_THRESHOLD", 185)
        set(value) = prefs.edit().putInt("KEY_VOLTAGE_LOW_THRESHOLD", value).apply()

    var voltageHighThreshold: Int
        get() = prefs.getInt("KEY_VOLTAGE_HIGH_THRESHOLD", 205)
        set(value) = prefs.edit().putInt("KEY_VOLTAGE_HIGH_THRESHOLD", value).apply()

    var voltageMaxAmperage: Int
        get() = prefs.getInt("KEY_VOLTAGE_MAX_AMPERAGE", 12)
        set(value) = prefs.edit().putInt("KEY_VOLTAGE_MAX_AMPERAGE", value).apply()

    var voltageMinAmperage: Int
        get() = prefs.getInt("KEY_VOLTAGE_MIN_AMPERAGE", 6)
        set(value) = prefs.edit().putInt("KEY_VOLTAGE_MIN_AMPERAGE", value).apply()

    var isDurationTimerEnabled: Boolean
        get() = prefs.getBoolean("KEY_DURATION_TIMER_ENABLED", false)
        set(value) = prefs.edit().putBoolean("KEY_DURATION_TIMER_ENABLED", value).apply()

    var targetDurationHours: Int
        get() = prefs.getInt("KEY_TARGET_DURATION_HOURS", 2).coerceIn(1, 12)
        set(value) = prefs.edit().putInt("KEY_TARGET_DURATION_HOURS", value.coerceIn(1, 12)).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun hasValidCredentials(): Boolean {
        return deviceSerial.isNotBlank() && internetPassword.isNotBlank()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove("KEY_DEVICE_SERIAL")
            .remove("KEY_INTERNET_PASSWORD")
            .apply()
    }
}
