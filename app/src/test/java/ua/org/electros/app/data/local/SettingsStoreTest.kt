package ua.org.electros.app.data.local

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
    fun earlyWriteBeforeInitialLoadCompletes_preservesExistingFieldsAndNewValue() = runBlocking {
        val file = tempFolder.newFile("early.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val seedDs = PreferenceDataStoreFactory.create(scope = seedScope) { file }
            val seedStore = SettingsStore(seedDs, seedScope)
            seedStore.awaitSettings()
            seedStore.setTargetKwhLimit(42.5)
            seedStore.setAutoResumeAfterOutage(false)
            seedStore.awaitIdle()
        } finally {
            seedScope.coroutineContext.job.cancelAndJoin()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
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
}

