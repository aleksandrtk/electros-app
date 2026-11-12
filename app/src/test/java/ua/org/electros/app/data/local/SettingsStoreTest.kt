package ua.org.electros.app.data.local

import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap

class SettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shiftScheduleHour_wrapsAroundMidnight() {
        assertThat(AppSettings.shiftScheduleHour("23:00", 1)).isEqualTo("00:00")
        assertThat(AppSettings.shiftScheduleHour("00:00", -1)).isEqualTo("23:00")
        assertThat(AppSettings.shiftScheduleHour("02:00", 1)).isEqualTo("03:00")
        assertThat(AppSettings.shiftScheduleHour("16:00", -1)).isEqualTo("15:00")
        assertThat(AppSettings.shiftScheduleHour("bad", 2)).isEqualTo("02:00")
    }

    @Test
    fun decode_returnsDefaultsAndRepairsInvertedThresholds() {
        val defaults = SettingsStore.decode(emptyPreferences())
        assertThat(defaults).isEqualTo(AppSettings())

        val (low, high) = AppSettings.normalizeThresholds(rawLow = 200, rawHigh = 195)
        assertThat(high - low).isAtLeast(AppSettings.MIN_HYSTERESIS_V)
    }

    @Test
    fun migration_copiesNonSecretSettingsAndCleansUpOldKeysWithoutTouchingCredentials() = runBlocking {
        val legacyPrefs = FakeSharedPreferences(
            mutableMapOf(
                "KEY_DEVICE_SERIAL" to "E999888",
                "KEY_INTERNET_PASSWORD" to "s3cr3t!",
                "KEY_PREFERRED_AMPERAGE" to 24,
                "KEY_KWH_LIMIT_ENABLED" to true,
                "KEY_TARGET_KWH_LIMIT" to 42.5f,
                "KEY_DURATION_TIMER_ENABLED" to true,
                "KEY_TARGET_DURATION_HOURS" to 99, // clamped to 12 on migration/decode
                "KEY_SCHEDULE_ENABLED" to true,
                "KEY_SCHEDULE_START" to "01:00",
                "KEY_SCHEDULE_STOP" to "06:00",
                "KEY_AUTO_RESUME" to false,
                "KEY_VOLTAGE_BALANCING_ENABLED" to false,
                "KEY_VOLTAGE_LOW_THRESHOLD" to 175,
                "KEY_VOLTAGE_HIGH_THRESHOLD" to 210,
                "KEY_VOLTAGE_MAX_AMPERAGE" to 14,
                "KEY_VOLTAGE_MIN_AMPERAGE" to 8
            )
        )

        val file = tempFolder.newFile("migrated.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                migrations = listOf(SettingsStore.legacyAuthPrefsMigration(legacyPrefs)),
                scope = scope,
                produceFile = { file }
            )
            val store = SettingsStore(dataStore, scope)

            val loaded = store.awaitSettings()

            assertThat(loaded.preferredAmperage).isEqualTo(24)
            assertThat(loaded.isKwhLimitEnabled).isTrue()
            assertThat(loaded.targetKwhLimit).isWithin(0.01).of(42.5)
            assertThat(loaded.isDurationTimerEnabled).isTrue()
            assertThat(loaded.targetDurationHours).isEqualTo(12)
            assertThat(loaded.isScheduleEnabled).isTrue()
            assertThat(loaded.scheduleStartTime).isEqualTo("01:00")
            assertThat(loaded.scheduleStopTime).isEqualTo("06:00")
            assertThat(loaded.autoResumeAfterOutage).isFalse()
            assertThat(loaded.isVoltageBalancingEnabled).isFalse()
            assertThat(loaded.voltageLowThreshold).isEqualTo(175)
            assertThat(loaded.voltageHighThreshold).isEqualTo(210)
            assertThat(loaded.voltageMaxAmperage).isEqualTo(14)
            assertThat(loaded.voltageMinAmperage).isEqualTo(8)

            // Credentials must remain untouched in AuthPreferences; migrated settings keys removed
            assertThat(legacyPrefs.getString("KEY_DEVICE_SERIAL", null)).isEqualTo("E999888")
            assertThat(legacyPrefs.getString("KEY_INTERNET_PASSWORD", null)).isEqualTo("s3cr3t!")
            AuthPreferences.LEGACY_SETTINGS_KEYS.forEach { key ->
                assertThat(legacyPrefs.contains(key)).isFalse()
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun setters_updateFlowImmediatelyAndPersistAcrossInstances() = runBlocking {
        val file = tempFolder.newFile("persist.preferences_pb")

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { file }
            val store1 = SettingsStore(ds1, scope1)
            store1.awaitSettings()

            store1.setPreferredAmperage(28)
            store1.setKwhLimitEnabled(true)
            store1.setTargetKwhLimit(33.5)
            store1.adjustTargetKwhLimit(2.5)
            store1.setDurationTimer(enabled = true, hours = 6)
            store1.setSchedule(enabled = true, startTime = "22:00", stopTime = "05:00")
            store1.setAutoResumeAfterOutage(false)
            store1.setVoltageLowThreshold(220) // clamped against default 205 -> 195
            store1.setVoltageHighThreshold(215)
            store1.setVoltageMaxAmperage(14)

            // Optimistic state is available immediately before waiting on disk
            assertThat(store1.settingsFlow.value.preferredAmperage).isEqualTo(28)
            assertThat(store1.settingsFlow.value.targetKwhLimit).isWithin(0.01).of(36.0)
            assertThat(store1.settingsFlow.value.voltageLowThreshold).isEqualTo(195)

            store1.awaitIdle()
        } finally {
            scope1.coroutineContext.job.cancelAndJoin()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { file }
            val store2 = SettingsStore(ds2, scope2)
            val reloaded = store2.awaitSettings()

            assertThat(reloaded.preferredAmperage).isEqualTo(28)
            assertThat(reloaded.isKwhLimitEnabled).isTrue()
            assertThat(reloaded.targetKwhLimit).isWithin(0.01).of(36.0)
            assertThat(reloaded.isDurationTimerEnabled).isTrue()
            assertThat(reloaded.targetDurationHours).isEqualTo(6)
            assertThat(reloaded.isScheduleEnabled).isTrue()
            assertThat(reloaded.scheduleStartTime).isEqualTo("22:00")
            assertThat(reloaded.scheduleStopTime).isEqualTo("05:00")
            assertThat(reloaded.autoResumeAfterOutage).isFalse()
            assertThat(reloaded.voltageLowThreshold).isEqualTo(195)
            assertThat(reloaded.voltageHighThreshold).isEqualTo(215)
            assertThat(reloaded.voltageMaxAmperage).isEqualTo(14)
        } finally {
            scope2.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun rapidSequentialEdits_convergeToFinalValue() = runBlocking {
        val file = tempFolder.newFile("rapid.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = SettingsStore(ds, scope)
            store.awaitSettings()

            for (amp in 6..32) {
                store.setPreferredAmperage(amp)
            }
            assertThat(store.settingsFlow.value.preferredAmperage).isEqualTo(32)

            store.awaitIdle()
            assertThat(store.settingsFlow.value.preferredAmperage).isEqualTo(32)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }

        val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dsReload = PreferenceDataStoreFactory.create(scope = reloadScope) { file }
            val reloadedStore = SettingsStore(dsReload, reloadScope)
            assertThat(reloadedStore.awaitSettings().preferredAmperage).isEqualTo(32)
        } finally {
            reloadScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun earlyWriteBeforeInitialLoadCompletes_preservesMigratedFieldsAndNewValue() = runBlocking {
        val legacyPrefs = FakeSharedPreferences(
            mutableMapOf(
                "KEY_TARGET_KWH_LIMIT" to 42.5f,
                "KEY_AUTO_RESUME" to false
            )
        )
        val file = tempFolder.newFile("early.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(
                migrations = listOf(SettingsStore.legacyAuthPrefsMigration(legacyPrefs)),
                scope = scope,
                produceFile = { file }
            )
            val store = SettingsStore(ds, scope)

            store.setPreferredAmperage(26)
            store.awaitIdle()

            val loaded = store.awaitSettings()
            assertThat(loaded.preferredAmperage).isEqualTo(26)
            assertThat(loaded.targetKwhLimit).isWithin(0.01).of(42.5)
            assertThat(loaded.autoResumeAfterOutage).isFalse()
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun setKwhLimit_persistsEnabledStateAndQuotaTogether() = runBlocking {
        val file = tempFolder.newFile("kwh_limit.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = SettingsStore(ds, scope)
            store.awaitSettings()

            store.setKwhLimit(enabled = true, quota = 16.0)
            store.awaitIdle()

            val enabledState = store.awaitSettings()
            assertThat(enabledState.isKwhLimitEnabled).isTrue()
            assertThat(enabledState.targetKwhLimit).isWithin(0.001).of(16.0)

            store.setKwhLimit(enabled = false, quota = 24.0)
            store.awaitIdle()

            val disabledState = store.awaitSettings()
            assertThat(disabledState.isKwhLimitEnabled).isFalse()
            assertThat(disabledState.targetKwhLimit).isWithin(0.001).of(24.0)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private class FakeSharedPreferences(
        initial: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any?>(initial)

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? =
            (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                pending[key] = values
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                pending[key] = value
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                pending[key] = value
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                pending[key] = value
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                pending[key] = value
            }
            override fun remove(key: String): SharedPreferences.Editor = apply {
                pending[key] = null
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }
}

