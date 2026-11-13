package ua.org.electros.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Stores station credentials encrypted via Android Keystore AES-256-GCM ([SecretCipher])
 * inside a dedicated Jetpack [DataStore]. Non-secret charging settings live in [SettingsStore].
 */
class AuthPreferences internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val scope: CoroutineScope
) {
    constructor(context: Context) : this(
        dataStore = getOrCreateDataStore(context.applicationContext),
        cipher = AndroidKeystoreCipher(),
        scope = sharedScope
    )

    private val lock = Any()
    private val initialLoad = CompletableDeferred<Unit>()
    private val pendingEdits = ArrayDeque<(MutablePreferences) -> Unit>()
    private var writerJob: Job? = null

    private var cachedSerial: String = ""
    private var cachedPassword: String = ""

    init {
        scope.launch {
            val prefs = dataStore.data
                .catch { ex ->
                    if (ex is IOException) emit(emptyPreferences()) else throw ex
                }
                .first()
            synchronized(lock) {
                if (!initialLoad.isCompleted) {
                    cachedSerial = prefs[KEY_ENC_DEVICE_SERIAL]?.let(cipher::decryptOrNull).orEmpty()
                    cachedPassword = prefs[KEY_ENC_INTERNET_PASSWORD]?.let(cipher::decryptOrNull).orEmpty()
                    initialLoad.complete(Unit)
                }
            }
        }
    }

    private fun ensureLoaded() {
        if (!initialLoad.isCompleted) {
            runBlocking { initialLoad.await() }
        }
    }

    var deviceSerial: String
        get() {
            ensureLoaded()
            return synchronized(lock) { cachedSerial }
        }
        set(value) {
            ensureLoaded()
            val trimmed = value.trim()
            enqueueWrite(
                updateMemory = { cachedSerial = trimmed },
                mutatePrefs = { prefs ->
                    if (trimmed.isEmpty()) {
                        prefs.remove(KEY_ENC_DEVICE_SERIAL)
                    } else {
                        prefs[KEY_ENC_DEVICE_SERIAL] = cipher.encrypt(trimmed)
                    }
                }
            )
        }

    var internetPassword: String
        get() {
            ensureLoaded()
            return synchronized(lock) { cachedPassword }
        }
        set(value) {
            ensureLoaded()
            val trimmed = value.trim()
            enqueueWrite(
                updateMemory = { cachedPassword = trimmed },
                mutatePrefs = { prefs ->
                    if (trimmed.isEmpty()) {
                        prefs.remove(KEY_ENC_INTERNET_PASSWORD)
                    } else {
                        prefs[KEY_ENC_INTERNET_PASSWORD] = cipher.encrypt(trimmed)
                    }
                }
            )
        }

    fun hasValidCredentials(): Boolean {
        ensureLoaded()
        return synchronized(lock) {
            cachedSerial.isNotBlank() && cachedPassword.isNotBlank()
        }
    }

    fun clearCredentials() {
        ensureLoaded()
        enqueueWrite(
            updateMemory = {
                cachedSerial = ""
                cachedPassword = ""
            },
            mutatePrefs = { prefs ->
                prefs.remove(KEY_ENC_DEVICE_SERIAL)
                prefs.remove(KEY_ENC_INTERNET_PASSWORD)
            }
        )
    }

    internal suspend fun awaitIdle() {
        initialLoad.await()
        while (true) {
            val job = synchronized(lock) { writerJob } ?: break
            job.join()
        }
    }

    private fun enqueueWrite(
        updateMemory: () -> Unit,
        mutatePrefs: (MutablePreferences) -> Unit
    ) {
        synchronized(lock) {
            updateMemory()
            pendingEdits.addLast(mutatePrefs)
            if (writerJob == null) {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    flushWrites()
                }
                writerJob = job
                job.start()
            }
        }
    }

    private suspend fun flushWrites() {
        while (true) {
            val batch = synchronized(lock) {
                if (pendingEdits.isEmpty()) {
                    writerJob = null
                    return
                }
                ArrayList(pendingEdits).also { pendingEdits.clear() }
            }
            var finishedNormally = false
            try {
                try {
                    dataStore.edit { prefs ->
                        batch.forEach { editFn -> editFn(prefs) }
                    }
                } catch (_: IOException) {
                }
                val done = synchronized(lock) {
                    val empty = pendingEdits.isEmpty()
                    if (empty) writerJob = null
                    empty
                }
                finishedNormally = true
                if (done) return
            } finally {
                if (!finishedNormally) {
                    synchronized(lock) { writerJob = null }
                }
            }
        }
    }

    companion object {
        internal const val DATASTORE_FILE_NAME = "auth_credentials"
        internal val KEY_ENC_DEVICE_SERIAL = stringPreferencesKey("enc_device_serial")
        internal val KEY_ENC_INTERNET_PASSWORD = stringPreferencesKey("enc_internet_password")

        private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var sharedDataStore: DataStore<Preferences>? = null

        private fun getOrCreateDataStore(appContext: Context): DataStore<Preferences> {
            return sharedDataStore ?: synchronized(this) {
                sharedDataStore ?: PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = sharedScope,
                    produceFile = { appContext.preferencesDataStoreFile(DATASTORE_FILE_NAME) }
                ).also { sharedDataStore = it }
            }
        }
    }
}
