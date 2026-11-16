package ua.org.electros.app.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.local.SecretCipher
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.network.CookieStorage
import ua.org.electros.app.data.network.ElectrosWebScraper
import ua.org.electros.app.data.repository.ChargeRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private object IdentityCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decryptOrNull(encoded: String): String? =
            encoded.removePrefix("enc:").takeIf { encoded.startsWith("enc:") }
    }

    @Test
    fun updateCredentials_withBlankPassword_rejectsAndPreservesStoredPassword() = runBlocking {
        val mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) {
                tempFolder.newFile("settings_blank.preferences_pb")
            }
            val authPrefs = AuthPreferences(ds, IdentityCipher, scope)
            authPrefs.deviceSerial = "a1234"
            authPrefs.internetPassword = "original-password"
            authPrefs.awaitIdle()

            val vm = SettingsViewModel(authPrefs)
            vm.updateCredentials("a1234", "")

            assertThat(vm.uiState.value.credentialsSaved).isFalse()
            assertThat(vm.uiState.value.credentialsError).isNotEmpty()
            assertThat(authPrefs.internetPassword).isEqualTo("original-password")
            assertThat(authPrefs.hasValidCredentials()).isTrue()
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun updateCredentials_verifiesLogin_andLogoutClearsSessionAndCookies() = runBlocking {
        val mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) {
                tempFolder.newFile("settings_login_logout.preferences_pb")
            }
            val authPrefs = AuthPreferences(ds, IdentityCipher, scope)
            val cookieStorage = CookieStorage()
            val client = OkHttpClient.Builder()
                .cookieJar(cookieStorage)
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    val builder = Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                    if (req.url.encodedPath.contains("login.php")) {
                        builder.addHeader("Set-Cookie", "PHPSESSID=abc123; Path=/")
                    }
                    builder
                        .body("<html><body>Стан: <span>Підключено</span></body></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                })
                .build()

            val scraper = ElectrosWebScraper(
                cookieStorage = cookieStorage,
                authPreferences = authPrefs,
                client = client,
                wsFactory = { _, listener ->
                    val dummyWs = object : okhttp3.WebSocket {
                        override fun request() = okhttp3.Request.Builder().url("https://electros.org.ua").build()
                        override fun queueSize() = 0L
                        override fun send(text: String) = false
                        override fun send(bytes: okio.ByteString) = false
                        override fun close(code: Int, reason: String?) = true
                        override fun cancel() = Unit
                    }
                    listener.onFailure(dummyWs, java.io.IOException("no ws in test"), null)
                    dummyWs
                }
            )
            val dao = MinimalFakeDao()
            val repo = ChargeRepository(scraper = scraper, chargeDao = dao, authPreferences = authPrefs)
            val vm = SettingsViewModel(authPrefs, repo)

            var savedCallbackCalled = false
            vm.updateCredentials("b5678", "new-secret") { savedCallbackCalled = true }
            withTimeout(3_000L) {
                while (!savedCallbackCalled) delay(10L)
            }
            authPrefs.awaitIdle()

            assertThat(savedCallbackCalled).isTrue()
            assertThat(vm.uiState.value.credentialsSaved).isTrue()
            assertThat(authPrefs.deviceSerial).isEqualTo("b5678")
            assertThat(authPrefs.internetPassword).isEqualTo("new-secret")

            var logoutCompleted = false
            vm.logout { logoutCompleted = true }
            withTimeout(3_000L) {
                while (!logoutCompleted) delay(10L)
            }
            authPrefs.awaitIdle()

            assertThat(logoutCompleted).isTrue()
            assertThat(authPrefs.hasValidCredentials()).isFalse()
            assertThat(vm.uiState.value.deviceSerial).isEmpty()
            assertThat(dao.cancelledActiveCount).isEqualTo(1)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class MinimalFakeDao : ChargeDao {
        var cancelledActiveCount = 0
        private val emptySessions = MutableStateFlow<List<ChargeSessionEntity>>(emptyList())
        private val emptySlices = MutableStateFlow<List<ChargeSliceEntity>>(emptyList())

        override suspend fun insertSession(session: ChargeSessionEntity) = Unit
        override suspend fun updateSession(session: ChargeSessionEntity) = Unit
        override suspend fun getSessionById(sessionId: String): ChargeSessionEntity? = null
        override suspend fun getActiveSession(): ChargeSessionEntity? = null
        override fun observeActiveSession(): Flow<ChargeSessionEntity?> = MutableStateFlow(null)
        override suspend fun cancelAllActiveSessions(currentTime: Long) { cancelledActiveCount++ }
        override suspend fun updateSessionStatus(sessionId: String, status: SessionStatus, completedAt: Long?, lastTickAt: Long) = Unit
        override suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long) = Unit
        override suspend fun updateTargetDurationMinutes(sessionId: String, targetDurationMinutes: Int) = Unit
        override suspend fun updateTargetKwh(sessionId: String, targetKwh: Double) = Unit
        override suspend fun updateSessionAmperage(sessionId: String, amperes: Int) = Unit
        override suspend fun updateActiveSliceCounter(sessionId: String, counterKwh: Double) = Unit
        override fun getAllSessions(): Flow<List<ChargeSessionEntity>> = emptySessions
        override suspend fun insertSlice(slice: ChargeSliceEntity): Long = 1L
        override suspend fun updateSlice(slice: ChargeSliceEntity) = Unit
        override suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> = emptyList()
        override fun observeSlicesForSession(sessionId: String): Flow<List<ChargeSliceEntity>> = emptySlices
        override suspend fun getLatestSliceForSession(sessionId: String): ChargeSliceEntity? = null
    }
}
