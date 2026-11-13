package ua.org.electros.app.ui.dashboard

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.org.electros.app.data.local.SettingsStore
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.network.ElectrosWebScraper
import ua.org.electros.app.data.repository.ChargeRepository
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun setKwhLimit_whenIdle_savesPreferenceWithoutStartingSessionOrStation() = runViewModelTest { vmStore, dsScope ->
        val store = createSettingsStore("idle_kwh.preferences_pb", dsScope)
        val dao = FakeChargeDao()
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 1, selectedA = 16, energyKwh = 10.0, voltageV = 230)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            settingsStore = store,
            localTimeProvider = { LocalTime.of(12, 0) },
            clockMs = { 5_000L }
        )
        val viewModel = createViewModel(vmStore) {
            DashboardViewModel(
                repository = repo,
                settingsStore = store,
                isMonitorServiceRunning = { false },
                clockMs = { 5_000L }
            )
        }
        val collectJob = launch { viewModel.uiState.collect {} }

        viewModel.setKwhLimit(context = null, enabled = true, quotaKwh = 16.0)
        awaitCondition {
            viewModel.uiState.value.isKwhLimitEnabled &&
                viewModel.uiState.value.targetQuotaKwh == 16.0
        }

        assertThat(dao.getActiveSession()).isNull()
        assertThat(viewModel.uiState.value.activeTargetKwh).isWithin(0.001).of(16.0)
        assertThat(wsCommands.any { it.contains("\nstart\n") }).isFalse()

        collectJob.cancelAndJoin()
    }

    @Test
    fun setKwhLimit_whenSessionActive_updatesTargetKwhWithoutSendingStart() = runViewModelTest { vmStore, dsScope ->
        val store = createSettingsStore("active_kwh.preferences_pb", dsScope)
        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s1",
                targetKwh = 0.0,
                amperageSetting = 16,
                status = SessionStatus.CHARGING,
                startedAt = 1_000L,
                lastTickAt = 4_000L
            )
        )
        dao.insertSlice(
            ChargeSliceEntity(
                sessionId = "s1",
                sliceIndex = 0,
                stationCounterStartKwh = 100.0,
                stationCounterLastKwh = 102.5,
                startedAt = 1_000L
            )
        )
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 2, selectedA = 16, energyKwh = 102.5, powerKw = 3.6, voltageV = 230)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            settingsStore = store,
            localTimeProvider = { LocalTime.of(12, 0) },
            clockMs = { 5_000L }
        )
        val viewModel = createViewModel(vmStore) {
            DashboardViewModel(
                repository = repo,
                settingsStore = store,
                isMonitorServiceRunning = { true },
                clockMs = { 5_000L }
            )
        }
        val collectJob = launch { viewModel.uiState.collect {} }

        viewModel.refreshTelemetry()
        awaitCondition { viewModel.uiState.value.activeSession?.id == "s1" }

        viewModel.setKwhLimit(context = null, enabled = true, quotaKwh = 20.0)
        awaitCondition { dao.getSessionById("s1")?.targetKwh == 20.0 }

        assertThat(viewModel.uiState.value.activeTargetKwh).isWithin(0.001).of(20.0)
        assertThat(wsCommands.any { it.contains("\nstart\n") }).isFalse()

        collectJob.cancelAndJoin()
    }

    @Test
    fun toggleVoltageBalancing_updatesUiStateDirectly() = runViewModelTest { vmStore, dsScope ->
        val store = createSettingsStore("dlb_toggle.preferences_pb", dsScope)
        val dao = FakeChargeDao()
        val scraper = buildScraper(CopyOnWriteArrayList()) {
            wsPayload(statusCode = 1, selectedA = 16, energyKwh = 0.0)
        }
        val repo = ChargeRepository(scraper = scraper, chargeDao = dao, settingsStore = store)
        val viewModel = createViewModel(vmStore) {
            DashboardViewModel(repository = repo, settingsStore = store)
        }
        val collectJob = launch { viewModel.uiState.collect {} }

        assertThat(viewModel.uiState.value.isVoltageBalancingEnabled).isTrue()

        viewModel.toggleVoltageBalancing(false)
        awaitCondition { !viewModel.uiState.value.isVoltageBalancingEnabled }

        viewModel.toggleVoltageBalancing(true)
        awaitCondition { viewModel.uiState.value.isVoltageBalancingEnabled }

        collectJob.cancelAndJoin()
    }

    @Test
    fun onAmperageSelected_withStaleCachedCharging_verifiesFreshTelemetryAndSkipsWsStartWhenIdle() = runViewModelTest { vmStore, dsScope ->
        val store = createSettingsStore("amp_stale.preferences_pb", dsScope)
        val dao = FakeChargeDao()
        val wsCommands = CopyOnWriteArrayList<String>()
        val telemetryRef = AtomicReference(
            wsPayload(statusCode = 2, selectedA = 12, energyKwh = 50.0, powerKw = 2.7, voltageV = 230)
        )
        val scraper = buildScraper(wsCommands) { telemetryRef.get() }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            settingsStore = store,
            clockMs = { 5_000L }
        )
        val viewModel = createViewModel(vmStore) {
            DashboardViewModel(repository = repo, settingsStore = store, clockMs = { 5_000L })
        }
        val collectJob = launch { viewModel.uiState.collect {} }

        viewModel.refreshTelemetry()
        awaitCondition { viewModel.uiState.value.telemetry?.isChargingActive == true }

        // Station has stopped charging in the meantime before user taps amperage
        telemetryRef.set(wsPayload(statusCode = 1, selectedA = 12, energyKwh = 50.0, powerKw = 0.0, voltageV = 230))

        viewModel.onAmperageSelected(16)
        awaitCondition {
            viewModel.uiState.value.selectedAmperes == 16 &&
                viewModel.uiState.value.telemetry?.isChargingActive == false
        }

        assertThat(wsCommands.any { it.contains("\nstart\n") }).isFalse()

        collectJob.cancelAndJoin()
    }

    @Test
    fun uiState_withOpenSliceAndLiveTelemetry_computesDeliveredKwhAndActiveTarget() = runViewModelTest { vmStore, dsScope ->
        val store = createSettingsStore("live_progress.preferences_pb", dsScope)
        store.setKwhLimit(true, 16.0)
        store.awaitIdle()

        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s1",
                targetKwh = 16.0,
                amperageSetting = 16,
                status = SessionStatus.CHARGING,
                startedAt = 1_000L,
                totalKwhDelivered = 0.0,
                lastTickAt = 2_000L
            )
        )
        dao.insertSlice(
            ChargeSliceEntity(
                sessionId = "s1",
                sliceIndex = 0,
                stationCounterStartKwh = 100.0,
                stationCounterLastKwh = 100.0,
                startedAt = 1_000L
            )
        )
        val scraper = buildScraper(CopyOnWriteArrayList()) {
            wsPayload(statusCode = 2, selectedA = 16, energyKwh = 104.25, powerKw = 3.5, voltageV = 230)
        }
        val repo = ChargeRepository(scraper = scraper, chargeDao = dao, settingsStore = store, clockMs = { 5_000L })
        val viewModel = createViewModel(vmStore) {
            DashboardViewModel(
                repository = repo,
                settingsStore = store,
                isMonitorServiceRunning = { true },
                clockMs = { 5_000L }
            )
        }
        val collectJob = launch { viewModel.uiState.collect {} }

        viewModel.refreshTelemetry()
        awaitCondition { viewModel.uiState.value.totalDeliveredKwh > 4.0 }

        assertThat(viewModel.uiState.value.totalDeliveredKwh).isWithin(0.01).of(4.25)
        assertThat(viewModel.uiState.value.activeTargetKwh).isWithin(0.01).of(16.0)

        collectJob.cancelAndJoin()
    }

    private fun runViewModelTest(
        block: suspend CoroutineScope.(vmStore: ViewModelStore, dsScope: CoroutineScope) -> Unit
    ) {
        val mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val vmStore = ViewModelStore()
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                block(vmStore, dsScope)
            }
        } finally {
            vmStore.clear()
            runBlocking { dsScope.coroutineContext.job.cancelAndJoin() }
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        vmStore: ViewModelStore,
        factoryFn: () -> DashboardViewModel
    ): DashboardViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factoryFn() as T
        }
        return ViewModelProvider(vmStore, factory)[DashboardViewModel::class.java]
    }

    private suspend fun createSettingsStore(fileName: String, scope: CoroutineScope): SettingsStore {
        val ds = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile(fileName)
        }
        return SettingsStore(ds, scope).also { it.awaitSettings() }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 3_000L, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20L)
        }
        assertThat(condition()).isTrue()
    }

    private class FakeWebSocket(
        private val req: Request,
        private val onSendText: (String, FakeWebSocket) -> Unit
    ) : WebSocket {
        override fun request(): Request = req
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            onSendText(text, this)
            return true
        }
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private fun dummyWsResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    private fun wsPayload(
        statusCode: Int,
        selectedA: Int = 16,
        measuredA: Double = 0.0,
        energyKwh: Double = 0.0,
        powerKw: Double = 0.0,
        voltageV: Int = 230
    ): String {
        val lines = MutableList(22) { "0" }
        lines[7] = selectedA.toString()
        lines[8] = statusCode.toString()
        lines[9] = (measuredA * 10.0).toInt().toString()
        lines[10] = voltageV.toString()
        lines[11] = (energyKwh * 1_000_000.0).toLong().toString()
        lines[12] = (powerKw * 10_000.0).toInt().toString()
        lines[13] = "2"
        lines[16] = "235"
        lines[17] = "225"
        lines[20] = voltageV.toString()
        return lines.joinToString("\n")
    }

    private fun buildScraper(
        wsCommands: MutableList<String>,
        telemetryProvider: () -> String
    ): ElectrosWebScraper {
        val factory = WebSocket.Factory { request, listener ->
            lateinit var ws: FakeWebSocket
            ws = FakeWebSocket(request) { text, fakeWs ->
                wsCommands.add(text)
                Thread {
                    if (text == "site 12345") {
                        listener.onMessage(fakeWs, telemetryProvider())
                    } else {
                        listener.onMessage(fakeWs, "ok")
                    }
                }.start()
            }
            Thread { listener.onOpen(ws, dummyWsResponse(request)) }.start()
            ws
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()

        return ElectrosWebScraper(
            client = client,
            wsFactory = factory,
            wsAckWaitMs = 200L,
            verifyDelayMs = 0L,
            serialProvider = { "12345" }
        )
    }

    private class FakeChargeDao : ChargeDao {
        private val sessions = linkedMapOf<String, ChargeSessionEntity>()
        private val slices = mutableListOf<ChargeSliceEntity>()
        private val sessionsFlow = MutableStateFlow<List<ChargeSessionEntity>>(emptyList())
        private val slicesFlow = MutableStateFlow<List<ChargeSliceEntity>>(emptyList())
        private var nextSliceId = 1L
        private val activeStatuses = setOf(
            SessionStatus.CHARGING,
            SessionStatus.OUTAGE_DETECTED,
            SessionStatus.OUTAGE_STABILIZING,
            SessionStatus.AUTO_RESUMING,
            SessionStatus.STOP_PENDING
        )

        private fun publish() {
            sessionsFlow.value = sessions.values.sortedByDescending { it.startedAt }
            slicesFlow.value = slices.toList()
        }

        override suspend fun insertSession(session: ChargeSessionEntity) {
            sessions[session.id] = session
            publish()
        }

        override suspend fun updateSession(session: ChargeSessionEntity) {
            sessions[session.id] = session
            publish()
        }

        override suspend fun getSessionById(sessionId: String): ChargeSessionEntity? = sessions[sessionId]

        override suspend fun getActiveSession(): ChargeSessionEntity? =
            sessions.values.filter { it.status in activeStatuses }.maxByOrNull { it.startedAt }

        override fun observeActiveSession(): Flow<ChargeSessionEntity?> =
            sessionsFlow.map { list -> list.firstOrNull { it.status in activeStatuses } }

        override suspend fun cancelAllActiveSessions(currentTime: Long) {
            sessions.forEach { (id, session) ->
                if (session.status in activeStatuses) {
                    sessions[id] = session.copy(status = SessionStatus.CANCELLED, completedAt = currentTime)
                }
            }
            publish()
        }

        override suspend fun updateSessionStatus(
            sessionId: String,
            status: SessionStatus,
            completedAt: Long?,
            lastTickAt: Long
        ) {
            val cur = sessions[sessionId] ?: return
            sessions[sessionId] = cur.copy(status = status, completedAt = completedAt, lastTickAt = lastTickAt)
            publish()
        }

        override suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long) {
            val cur = sessions[sessionId] ?: return
            sessions[sessionId] = cur.copy(accumulatedChargingMs = accumulatedMs, lastTickAt = lastTickAt)
            publish()
        }

        override suspend fun updateTargetDurationMinutes(sessionId: String, targetDurationMinutes: Int) {
            val cur = sessions[sessionId] ?: return
            sessions[sessionId] = cur.copy(targetDurationMinutes = targetDurationMinutes)
            publish()
        }

        override suspend fun updateTargetKwh(sessionId: String, targetKwh: Double) {
            val cur = sessions[sessionId] ?: return
            sessions[sessionId] = cur.copy(targetKwh = targetKwh)
            publish()
        }

        override suspend fun updateSessionAmperage(sessionId: String, amperes: Int) {
            val cur = sessions[sessionId] ?: return
            sessions[sessionId] = cur.copy(amperageSetting = amperes)
            publish()
        }

        override suspend fun updateActiveSliceCounter(sessionId: String, counterKwh: Double) {
            val idx = slices.indexOfLast { it.sessionId == sessionId && it.endedAt == null }
            if (idx >= 0) {
                val cur = slices[idx]
                if (counterKwh > cur.stationCounterLastKwh) {
                    slices[idx] = cur.copy(stationCounterLastKwh = counterKwh)
                }
            }
        }

        override fun getAllSessions(): Flow<List<ChargeSessionEntity>> = sessionsFlow

        override suspend fun insertSlice(slice: ChargeSliceEntity): Long {
            val id = nextSliceId++
            slices.add(slice.copy(id = id))
            publish()
            return id
        }

        override suspend fun updateSlice(slice: ChargeSliceEntity) {
            val idx = slices.indexOfFirst { it.id == slice.id }
            if (idx >= 0) slices[idx] = slice
            publish()
        }

        override suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> =
            slices.filter { it.sessionId == sessionId }.sortedBy { it.sliceIndex }

        override fun observeSlicesForSession(sessionId: String): Flow<List<ChargeSliceEntity>> =
            slicesFlow.map { list -> list.filter { it.sessionId == sessionId }.sortedBy { it.sliceIndex } }

        override suspend fun getLatestSliceForSession(sessionId: String): ChargeSliceEntity? =
            slices.filter { it.sessionId == sessionId }.maxByOrNull { it.sliceIndex }
    }
}
