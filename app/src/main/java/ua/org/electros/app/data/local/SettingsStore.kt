package ua.org.electros.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class AppSettings(
    val preferredAmperage: Int = DEFAULT_PREFERRED_AMPERAGE,
    val isKwhLimitEnabled: Boolean = false,
    val targetKwhLimit: Double = DEFAULT_TARGET_KWH_LIMIT,
    val isDurationTimerEnabled: Boolean = false,
    val targetDurationHours: Int = DEFAULT_TARGET_DURATION_HOURS,
    val isScheduleEnabled: Boolean = false,
    val scheduleStartTime: String = DEFAULT_SCHEDULE_START,
    val scheduleStopTime: String = DEFAULT_SCHEDULE_STOP,
    val autoResumeAfterOutage: Boolean = true,
    val isVoltageBalancingEnabled: Boolean = true,
    val voltageLowThreshold: Int = DEFAULT_VOLTAGE_LOW_THRESHOLD,
    val voltageHighThreshold: Int = DEFAULT_VOLTAGE_HIGH_THRESHOLD,
    val voltageMaxAmperage: Int = DEFAULT_VOLTAGE_MAX_AMPERAGE,
    val voltageMinAmperage: Int = DEFAULT_VOLTAGE_MIN_AMPERAGE
) {
    companion object {
        const val DEFAULT_PREFERRED_AMPERAGE = 16
        const val DEFAULT_TARGET_KWH_LIMIT = 15.0
        const val DEFAULT_TARGET_DURATION_HOURS = 2
        const val DEFAULT_SCHEDULE_START = "23:00"
        const val DEFAULT_SCHEDULE_STOP = "07:00"
        const val DEFAULT_VOLTAGE_LOW_THRESHOLD = 185
        const val DEFAULT_VOLTAGE_HIGH_THRESHOLD = 205
        const val DEFAULT_VOLTAGE_MAX_AMPERAGE = 12
        const val DEFAULT_VOLTAGE_MIN_AMPERAGE = 6
        const val MIN_HYSTERESIS_V = 10

        fun shiftScheduleHour(hhmm: String, delta: Int): String {
            val currentHour = hhmm.substringBefore(":").toIntOrNull()?.coerceIn(0, 23) ?: 0
            val nextHour = (currentHour + delta + 24) % 24
            return "%02d:00".format(nextHour)
        }

        internal fun clampLowThreshold(requestedLow: Int, currentHigh: Int): Int {
            val maxLow = (currentHigh - MIN_HYSTERESIS_V).coerceAtLeast(160)
            return requestedLow.coerceIn(160, minOf(200, maxLow))
        }

        internal fun clampHighThreshold(requestedHigh: Int, currentLow: Int): Int {
            val minHigh = (currentLow + MIN_HYSTERESIS_V).coerceAtMost(230)
            return requestedHigh.coerceIn(maxOf(195, minHigh), 230)
        }

        internal fun normalizeThresholds(rawLow: Int, rawHigh: Int): Pair<Int, Int> {
            val low = rawLow.coerceIn(160, 200)
            val high = clampHighThreshold(rawHigh, low)
            return low to high
        }
    }
}

/**
 * Stores non-secret charging preferences in Jetpack DataStore and keeps an in-memory [StateFlow]
 * updated synchronously on UI edits while serializing writes to disk asynchronously.
 */
class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope
) {
    private val stateLock = Any()
    private val initialLoad = CompletableDeferred<Unit>()
    private val pendingEdits = ArrayDeque<(MutablePreferences) -> Unit>()
    private var activeWriterJob: Job? = null
    private var hasAnyLocalWrite = false

    private val _settings = MutableStateFlow(AppSettings())
    val settingsFlow: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .collect { prefs ->
                    synchronized(stateLock) {
                        // Once SettingsStore performs a write, flushPendingEdits() becomes the sole
                        // authoritative disk -> StateFlow publisher so delayed pre-edit snapshots from
                        // dataStore.data cannot overwrite newer committed edits.
                        if (!hasAnyLocalWrite) {
                            _settings.value = decode(prefs)
                            initialLoad.complete(Unit)
                        }
                    }
                }
        }
    }

    /** Waits for the initial disk load/migration once, then returns the current [AppSettings]. */
    suspend fun awaitSettings(): AppSettings {
        initialLoad.await()
        return _settings.value
    }

    fun currentSettings(): AppSettings = _settings.value

    internal suspend fun awaitIdle() {
        initialLoad.await()
        while (true) {
            val job = synchronized(stateLock) { activeWriterJob } ?: break
            job.join()
        }
    }

    fun setPreferredAmperage(amperes: Int) {
        update(
            optimistic = { it.copy(preferredAmperage = amperes) },
            persist = { it[KEY_PREFERRED_AMPERAGE] = amperes }
        )
    }

    fun setKwhLimitEnabled(enabled: Boolean) {
        update(
            optimistic = { it.copy(isKwhLimitEnabled = enabled) },
            persist = { it[KEY_KWH_LIMIT_ENABLED] = enabled }
        )
    }

    fun setTargetKwhLimit(quota: Double) {
        val clamped = quota.coerceIn(1.0, 100.0)
        update(
            optimistic = { it.copy(targetKwhLimit = clamped) },
            persist = { it[KEY_TARGET_KWH_LIMIT] = clamped }
        )
    }

    fun adjustTargetKwhLimit(delta: Double) {
        update(
            optimistic = {
                it.copy(targetKwhLimit = (it.targetKwhLimit + delta).coerceIn(1.0, 100.0))
            },
            persist = { prefs ->
                val current = prefs[KEY_TARGET_KWH_LIMIT] ?: AppSettings.DEFAULT_TARGET_KWH_LIMIT
                prefs[KEY_TARGET_KWH_LIMIT] = (current + delta).coerceIn(1.0, 100.0)
            }
        )
    }

    fun setKwhLimit(enabled: Boolean, quota: Double) {
        val clamped = quota.coerceIn(1.0, 100.0)
        update(
            optimistic = { it.copy(isKwhLimitEnabled = enabled, targetKwhLimit = clamped) },
            persist = { prefs ->
                prefs[KEY_KWH_LIMIT_ENABLED] = enabled
                prefs[KEY_TARGET_KWH_LIMIT] = clamped
            }
        )
    }

    fun setDurationTimerEnabled(enabled: Boolean) {
        update(
            optimistic = { it.copy(isDurationTimerEnabled = enabled) },
            persist = { it[KEY_DURATION_TIMER_ENABLED] = enabled }
        )
    }

    fun setTargetDurationHours(hours: Int) {
        val clamped = hours.coerceIn(1, 12)
        update(
            optimistic = { it.copy(targetDurationHours = clamped) },
            persist = { it[KEY_TARGET_DURATION_HOURS] = clamped }
        )
    }

    fun setDurationTimer(enabled: Boolean, hours: Int) {
        val clamped = hours.coerceIn(1, 12)
        update(
            optimistic = { it.copy(isDurationTimerEnabled = enabled, targetDurationHours = clamped) },
            persist = { prefs ->
                prefs[KEY_DURATION_TIMER_ENABLED] = enabled
                prefs[KEY_TARGET_DURATION_HOURS] = clamped
            }
        )
    }

    fun setScheduleEnabled(enabled: Boolean) {
        update(
            optimistic = { it.copy(isScheduleEnabled = enabled) },
            persist = { it[KEY_SCHEDULE_ENABLED] = enabled }
        )
    }

    fun setScheduleTimes(startTime: String, stopTime: String) {
        update(
            optimistic = { it.copy(scheduleStartTime = startTime, scheduleStopTime = stopTime) },
            persist = { prefs ->
                prefs[KEY_SCHEDULE_START] = startTime
                prefs[KEY_SCHEDULE_STOP] = stopTime
            }
        )
    }

    fun setSchedule(enabled: Boolean, startTime: String, stopTime: String) {
        update(
            optimistic = {
                it.copy(
                    isScheduleEnabled = enabled,
                    scheduleStartTime = startTime,
                    scheduleStopTime = stopTime
                )
            },
            persist = { prefs ->
                prefs[KEY_SCHEDULE_ENABLED] = enabled
                prefs[KEY_SCHEDULE_START] = startTime
                prefs[KEY_SCHEDULE_STOP] = stopTime
            }
        )
    }

    fun setAutoResumeAfterOutage(enabled: Boolean) {
        update(
            optimistic = { it.copy(autoResumeAfterOutage = enabled) },
            persist = { it[KEY_AUTO_RESUME] = enabled }
        )
    }

    fun setVoltageBalancingEnabled(enabled: Boolean) {
        update(
            optimistic = { it.copy(isVoltageBalancingEnabled = enabled) },
            persist = { it[KEY_VOLTAGE_BALANCING_ENABLED] = enabled }
        )
    }

    fun setVoltageLowThreshold(requestedLow: Int) {
        update(
            optimistic = { current ->
                current.copy(
                    voltageLowThreshold = AppSettings.clampLowThreshold(
                        requestedLow,
                        current.voltageHighThreshold
                    )
                )
            },
            persist = { prefs ->
                val currentHigh = prefs[KEY_VOLTAGE_HIGH_THRESHOLD]
                    ?: AppSettings.DEFAULT_VOLTAGE_HIGH_THRESHOLD
                prefs[KEY_VOLTAGE_LOW_THRESHOLD] = AppSettings.clampLowThreshold(
                    requestedLow,
                    currentHigh
                )
            }
        )
    }

    fun setVoltageHighThreshold(requestedHigh: Int) {
        update(
            optimistic = { current ->
                current.copy(
                    voltageHighThreshold = AppSettings.clampHighThreshold(
                        requestedHigh,
                        current.voltageLowThreshold
                    )
                )
            },
            persist = { prefs ->
                val currentLow = prefs[KEY_VOLTAGE_LOW_THRESHOLD]
                    ?: AppSettings.DEFAULT_VOLTAGE_LOW_THRESHOLD
                prefs[KEY_VOLTAGE_HIGH_THRESHOLD] = AppSettings.clampHighThreshold(
                    requestedHigh,
                    currentLow
                )
            }
        )
    }

    fun setVoltageMaxAmperage(amperage: Int) {
        val clamped = amperage.coerceIn(6, 16)
        update(
            optimistic = { it.copy(voltageMaxAmperage = clamped) },
            persist = { it[KEY_VOLTAGE_MAX_AMPERAGE] = clamped }
        )
    }

    private fun update(
        optimistic: (AppSettings) -> AppSettings,
        persist: (MutablePreferences) -> Unit
    ) {
        synchronized(stateLock) {
            hasAnyLocalWrite = true
            _settings.update(optimistic)
            pendingEdits.addLast(persist)
            if (activeWriterJob == null) {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    flushPendingEdits()
                }
                activeWriterJob = job
                job.start()
            }
        }
    }

    private suspend fun flushPendingEdits() {
        while (true) {
            val batch = synchronized(stateLock) {
                if (pendingEdits.isEmpty()) {
                    activeWriterJob = null
                    return
                }
                ArrayList(pendingEdits).also { pendingEdits.clear() }
            }

            var completedNormally = false
            try {
                val committed = try {
                    dataStore.edit { prefs ->
                        batch.forEach { applyEdit -> applyEdit(prefs) }
                    }
                } catch (_: IOException) {
                    if (!initialLoad.isCompleted) {
                        runCatching { dataStore.data.first() }.getOrNull()
                            ?.toMutablePreferences()
                            ?.apply { batch.forEach { applyEdit -> applyEdit(this) } }
                    } else {
                        null
                    }
                }
                val done = synchronized(stateLock) {
                    val isIdle = pendingEdits.isEmpty()
                    if (isIdle) {
                        if (committed != null) {
                            _settings.value = decode(committed)
                        }
                        initialLoad.complete(Unit)
                        activeWriterJob = null
                    }
                    isIdle
                }
                completedNormally = true
                if (done) return
            } finally {
                if (!completedNormally) {
                    synchronized(stateLock) {
                        activeWriterJob = null
                    }
                }
            }
        }
    }

    companion object {
        private const val DATASTORE_FILE_NAME = "app_settings"

        internal val KEY_MIGRATED_FROM_AUTH_PREFS = booleanPreferencesKey("migrated_from_auth_prefs")
        internal val KEY_PREFERRED_AMPERAGE = intPreferencesKey("preferred_amperage")
        internal val KEY_KWH_LIMIT_ENABLED = booleanPreferencesKey("kwh_limit_enabled")
        internal val KEY_TARGET_KWH_LIMIT = doublePreferencesKey("target_kwh_limit")
        internal val KEY_DURATION_TIMER_ENABLED = booleanPreferencesKey("duration_timer_enabled")
        internal val KEY_TARGET_DURATION_HOURS = intPreferencesKey("target_duration_hours")
        internal val KEY_SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        internal val KEY_SCHEDULE_START = stringPreferencesKey("schedule_start")
        internal val KEY_SCHEDULE_STOP = stringPreferencesKey("schedule_stop")
        internal val KEY_AUTO_RESUME = booleanPreferencesKey("auto_resume")
        internal val KEY_VOLTAGE_BALANCING_ENABLED = booleanPreferencesKey("voltage_balancing_enabled")
        internal val KEY_VOLTAGE_LOW_THRESHOLD = intPreferencesKey("voltage_low_threshold")
        internal val KEY_VOLTAGE_HIGH_THRESHOLD = intPreferencesKey("voltage_high_threshold")
        internal val KEY_VOLTAGE_MAX_AMPERAGE = intPreferencesKey("voltage_max_amperage")
        internal val KEY_VOLTAGE_MIN_AMPERAGE = intPreferencesKey("voltage_min_amperage")

        @Volatile
        private var instance: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val dataStore = PreferenceDataStoreFactory.create(
                        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                        scope = ioScope,
                        produceFile = { appContext.preferencesDataStoreFile(DATASTORE_FILE_NAME) }
                    )
                    SettingsStore(dataStore, ioScope).also { instance = it }
                }
            }
        }

        internal fun legacyAuthPrefsMigration(legacyPrefs: SharedPreferences): DataMigration<Preferences> {
            return LegacyAuthPrefsMigration(legacyPrefs)
        }

        internal fun decode(prefs: Preferences): AppSettings {
            val (lowThreshold, highThreshold) = AppSettings.normalizeThresholds(
                rawLow = prefs[KEY_VOLTAGE_LOW_THRESHOLD]
                    ?: AppSettings.DEFAULT_VOLTAGE_LOW_THRESHOLD,
                rawHigh = prefs[KEY_VOLTAGE_HIGH_THRESHOLD]
                    ?: AppSettings.DEFAULT_VOLTAGE_HIGH_THRESHOLD
            )
            return AppSettings(
                preferredAmperage = prefs[KEY_PREFERRED_AMPERAGE]
                    ?: AppSettings.DEFAULT_PREFERRED_AMPERAGE,
                isKwhLimitEnabled = prefs[KEY_KWH_LIMIT_ENABLED] ?: false,
                targetKwhLimit = (prefs[KEY_TARGET_KWH_LIMIT]
                    ?: AppSettings.DEFAULT_TARGET_KWH_LIMIT).coerceIn(1.0, 100.0),
                isDurationTimerEnabled = prefs[KEY_DURATION_TIMER_ENABLED] ?: false,
                targetDurationHours = (prefs[KEY_TARGET_DURATION_HOURS]
                    ?: AppSettings.DEFAULT_TARGET_DURATION_HOURS).coerceIn(1, 12),
                isScheduleEnabled = prefs[KEY_SCHEDULE_ENABLED] ?: false,
                scheduleStartTime = prefs[KEY_SCHEDULE_START] ?: AppSettings.DEFAULT_SCHEDULE_START,
                scheduleStopTime = prefs[KEY_SCHEDULE_STOP] ?: AppSettings.DEFAULT_SCHEDULE_STOP,
                autoResumeAfterOutage = prefs[KEY_AUTO_RESUME] ?: true,
                isVoltageBalancingEnabled = prefs[KEY_VOLTAGE_BALANCING_ENABLED] ?: true,
                voltageLowThreshold = lowThreshold,
                voltageHighThreshold = highThreshold,
                voltageMaxAmperage = (prefs[KEY_VOLTAGE_MAX_AMPERAGE]
                    ?: AppSettings.DEFAULT_VOLTAGE_MAX_AMPERAGE).coerceIn(6, 16),
                voltageMinAmperage = (prefs[KEY_VOLTAGE_MIN_AMPERAGE]
                    ?: AppSettings.DEFAULT_VOLTAGE_MIN_AMPERAGE).coerceIn(6, 16)
            )
        }
    }
}

private class LegacyAuthPrefsMigration(
    private val legacyPrefs: SharedPreferences
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        if (currentData[SettingsStore.KEY_MIGRATED_FROM_AUTH_PREFS] == true) return false
        return AuthPreferences.LEGACY_SETTINGS_KEYS.any { legacyPrefs.contains(it) }
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        mutable.putIfAbsent(SettingsStore.KEY_PREFERRED_AMPERAGE, readInt("KEY_PREFERRED_AMPERAGE"))
        mutable.putIfAbsent(SettingsStore.KEY_KWH_LIMIT_ENABLED, readBoolean("KEY_KWH_LIMIT_ENABLED"))
        mutable.putIfAbsent(
            SettingsStore.KEY_TARGET_KWH_LIMIT,
            readDoubleFromFloat("KEY_TARGET_KWH_LIMIT")?.coerceIn(1.0, 100.0)
        )
        mutable.putIfAbsent(
            SettingsStore.KEY_DURATION_TIMER_ENABLED,
            readBoolean("KEY_DURATION_TIMER_ENABLED")
        )
        mutable.putIfAbsent(
            SettingsStore.KEY_TARGET_DURATION_HOURS,
            readInt("KEY_TARGET_DURATION_HOURS")?.coerceIn(1, 12)
        )
        mutable.putIfAbsent(SettingsStore.KEY_SCHEDULE_ENABLED, readBoolean("KEY_SCHEDULE_ENABLED"))
        mutable.putIfAbsent(SettingsStore.KEY_SCHEDULE_START, readString("KEY_SCHEDULE_START"))
        mutable.putIfAbsent(SettingsStore.KEY_SCHEDULE_STOP, readString("KEY_SCHEDULE_STOP"))
        mutable.putIfAbsent(SettingsStore.KEY_AUTO_RESUME, readBoolean("KEY_AUTO_RESUME"))
        mutable.putIfAbsent(
            SettingsStore.KEY_VOLTAGE_BALANCING_ENABLED,
            readBoolean("KEY_VOLTAGE_BALANCING_ENABLED")
        )
        mutable.putIfAbsent(
            SettingsStore.KEY_VOLTAGE_LOW_THRESHOLD,
            readInt("KEY_VOLTAGE_LOW_THRESHOLD")
        )
        mutable.putIfAbsent(
            SettingsStore.KEY_VOLTAGE_HIGH_THRESHOLD,
            readInt("KEY_VOLTAGE_HIGH_THRESHOLD")
        )
        mutable.putIfAbsent(
            SettingsStore.KEY_VOLTAGE_MAX_AMPERAGE,
            readInt("KEY_VOLTAGE_MAX_AMPERAGE")?.coerceIn(6, 16)
        )
        mutable.putIfAbsent(
            SettingsStore.KEY_VOLTAGE_MIN_AMPERAGE,
            readInt("KEY_VOLTAGE_MIN_AMPERAGE")?.coerceIn(6, 16)
        )
        mutable[SettingsStore.KEY_MIGRATED_FROM_AUTH_PREFS] = true
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() {
        val editor = legacyPrefs.edit()
        AuthPreferences.LEGACY_SETTINGS_KEYS.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.putIfAbsent(
        key: Preferences.Key<T>,
        value: T?
    ) {
        if (value != null && key !in this) {
            this[key] = value
        }
    }

    private fun readBoolean(key: String): Boolean? =
        if (legacyPrefs.contains(key)) runCatching { legacyPrefs.getBoolean(key, false) }.getOrNull() else null

    private fun readInt(key: String): Int? =
        if (legacyPrefs.contains(key)) runCatching { legacyPrefs.getInt(key, 0) }.getOrNull() else null

    private fun readDoubleFromFloat(key: String): Double? =
        if (legacyPrefs.contains(key)) runCatching { legacyPrefs.getFloat(key, 0f).toDouble() }.getOrNull() else null

    private fun readString(key: String): String? =
        if (legacyPrefs.contains(key)) runCatching { legacyPrefs.getString(key, null) }.getOrNull() else null
}
