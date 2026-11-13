package ua.org.electros.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Test
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.network.ElectrosWebScraper
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

class ChargeRepositoryLifecycleTest {

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
        httpPosts: MutableList<List<Pair<String, String>>> = CopyOnWriteArrayList(),
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
                val form = chain.request().body as? FormBody
                if (form != null) {
                    httpPosts.add((0 until form.size).map { i -> form.name(i) to form.value(i) })
                }
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

    @Test
    fun resumeAfterOutage_whenAutoResumeDisabled_failsAndSendsNoStartCommand() = runBlocking {
        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s1",
                targetKwh = 10.0,
                amperageSetting = 16,
                status = SessionStatus.OUTAGE_DETECTED,
                startedAt = 1_000L
            )
        )
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 1, selectedA = 16, energyKwh = 0.0)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            isAutoResumeEnabledProvider = { false }
        )

        val result = repo.resumeAfterOutage("s1")

        assertThat(result.isFailure).isTrue()
        assertThat(wsCommands).isEmpty()
        assertThat(dao.getSessionById("s1")?.status).isEqualTo(SessionStatus.OUTAGE_DETECTED)
    }

    @Test
    fun resumeAfterOutage_whenManual_startsStationAnchorsFreshCounterAndArmsDeadMan() = runBlocking {
        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s1",
                targetKwh = 10.0,
                amperageSetting = 16,
                status = SessionStatus.OUTAGE_DETECTED,
                startedAt = 1_000L
            )
        )
        dao.insertSlice(
            ChargeSliceEntity(
                sessionId = "s1",
                sliceIndex = 0,
                stationCounterStartKwh = 20.0,
                stationCounterLastKwh = 22.64,
                finalizedKwh = 2.64,
                startedAt = 1_000L,
                endedAt = 2_000L,
                terminationReason = SliceEndReason.POWER_OUTAGE
            )
        )
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            // Post-outage counter rebooted to 0.3 kWh, charging active at 230V 16A
            wsPayload(statusCode = 2, selectedA = 16, measuredA = 16.0, energyKwh = 0.3, powerKw = 3.68, voltageV = 230)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            isAutoResumeEnabledProvider = { false },
            scheduleConfigProvider = { Triple(false, "23:00", "07:00") },
            localTimeProvider = { LocalTime.of(10, 0) }
        )

        val result = repo.resumeAfterOutage("s1", isManual = true)

        assertThat(result.isSuccess).isTrue()
        assertThat(dao.getSessionById("s1")?.status).isEqualTo(SessionStatus.CHARGING)
        // Fresh slice anchored at 0.3 kWh instead of 0.0 or old 22.64
        val activeSlice = dao.getLatestSliceForSession("s1")!!
        assertThat(activeSlice.stationCounterStartKwh).isWithin(0.01).of(0.3)
        // Remaining 7.36 kWh @ 3.68 kW = 120m + 15m = 135m -> 10:00..12:15
        val timerCmd = wsCommands.firstOrNull { it.contains("\ndatetime\n") }
        assertThat(timerCmd).isNotNull()
        assertThat(timerCmd).contains("\n10:00\n12:15\n")
        // Verify start sent only once (no redundant setAmperage call before start)
        assertThat(wsCommands.count { it.contains("\nstart\n") }).isEqualTo(1)
    }

    @Test
    fun startNewSession_anchorsFreshTelemetryDoesNotSendDuplicateSetAmperageAndRestoresScheduleOnStop() = runBlocking {
        val dao = FakeChargeDao()
        var currentEnergy = 5.0
        var charging = false
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(
                statusCode = if (charging) 2 else 1,
                selectedA = 16,
                measuredA = if (charging) 16.0 else 0.0,
                energyKwh = currentEnergy,
                powerKw = if (charging) 3.68 else 0.0,
                voltageV = 230
            )
        }

        // First populate _telemetryState with stale 5.0 kWh reading
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            scheduleConfigProvider = { Triple(true, "23:00", "07:00") },
            localTimeProvider = { LocalTime.of(23, 30) }
        )
        repo.fetchLatestTelemetry()

        // Station counter actually jumped to 9.4 kWh before startNewSession
        currentEnergy = 9.4
        charging = true
        wsCommands.clear()

        val startRes = repo.startNewSession(targetKwh = 7.36, amperage = 16)
        assertThat(startRes.isSuccess).isTrue()
        val sessionId = startRes.getOrThrow()

        // Fresh anchor used 9.4 rather than stale 5.0
        val slice = dao.getLatestSliceForSession(sessionId)!!
        assertThat(slice.stationCounterStartKwh).isWithin(0.01).of(9.4)
        // Only one start WS command (no duplicate setAmperage)
        assertThat(wsCommands.count { it.contains("\nstart\n") }).isEqualTo(1)
        // Dead-man timer keeps schedule start 23:00 and sets 135 min stop (01:45 < 07:00)
        val armedCmd = wsCommands.last { it.contains("\ndatetime\n") }
        assertThat(armedCmd).contains("\n23:00\n01:45\n")

        // On finalize, user's 23:00..07:00 schedule is restored
        wsCommands.clear()
        repo.finalizeSessionStopped(sessionId, 16.8, SliceEndReason.TARGET_REACHED, SessionStatus.TARGET_REACHED)
        val restoreCmd = wsCommands.lastOrNull { it.contains("\ndatetime\n") }
        assertThat(restoreCmd).isNotNull()
        assertThat(restoreCmd).contains("\n23:00\n07:00\n")
    }

    @Test
    fun fetchLatestTelemetry_whenMonitorInactiveAndOnlineIdleWithProgress_marksVehicleStoppedEvenWithAutoResumeEnabled() = runBlocking {
        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s-idle-prog",
                targetKwh = 10.0,
                amperageSetting = 16,
                status = SessionStatus.CHARGING,
                startedAt = 1_000L,
                totalKwhDelivered = 2.5,
                lastTickAt = 40_000L
            )
        )
        dao.insertSlice(
            ChargeSliceEntity(
                sessionId = "s-idle-prog",
                sliceIndex = 0,
                stationCounterStartKwh = 10.0,
                stationCounterLastKwh = 12.5,
                startedAt = 1_000L
            )
        )
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 1, selectedA = 16, energyKwh = 12.5, voltageV = 228)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            isAutoResumeEnabledProvider = { true },
            clockMs = { 60_000L }
        )

        val result = repo.fetchLatestTelemetry(isMonitorServiceRunning = false)

        assertThat(result.isSuccess).isTrue()
        assertThat(dao.getActiveSession()).isNull()
        assertThat(dao.getSessionById("s-idle-prog")?.status).isEqualTo(SessionStatus.VEHICLE_STOPPED)
    }

    @Test
    fun fetchLatestTelemetry_whenMonitorInactiveAndOnlineIdleWithoutProgress_cancelsSession() = runBlocking {
        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s-idle-empty",
                targetKwh = 0.0,
                amperageSetting = 16,
                status = SessionStatus.CHARGING,
                startedAt = 1_000L
            )
        )
        dao.insertSlice(
            ChargeSliceEntity(
                sessionId = "s-idle-empty",
                sliceIndex = 0,
                stationCounterStartKwh = 10.0,
                stationCounterLastKwh = 10.0,
                startedAt = 1_000L
            )
        )
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 1, selectedA = 16, energyKwh = 10.0, voltageV = 228)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            isAutoResumeEnabledProvider = { true },
            clockMs = { 60_000L }
        )

        repo.fetchLatestTelemetry(isMonitorServiceRunning = false)

        assertThat(dao.getActiveSession()).isNull()
        assertThat(dao.getSessionById("s-idle-empty")?.status).isEqualTo(SessionStatus.CANCELLED)
    }

    @Test
    fun fetchLatestTelemetry_whenMonitorInactiveAndOfflineWithoutCharging_cancelsUnstartedSessionDespiteAutoResume() = runBlocking {
        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s-offline-empty",
                targetKwh = 10.0,
                amperageSetting = 16,
                status = SessionStatus.CHARGING,
                startedAt = 1_000L
            )
        )
        dao.insertSlice(
            ChargeSliceEntity(
                sessionId = "s-offline-empty",
                sliceIndex = 0,
                stationCounterStartKwh = 10.0,
                stationCounterLastKwh = 10.0,
                startedAt = 1_000L
            )
        )
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 7, selectedA = 16, energyKwh = 0.0, voltageV = 0)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            isAutoResumeEnabledProvider = { true },
            clockMs = { 5_000L }
        )

        repo.fetchLatestTelemetry(isMonitorServiceRunning = false)

        assertThat(dao.getActiveSession()).isNull()
        assertThat(dao.getSessionById("s-offline-empty")?.status).isEqualTo(SessionStatus.CANCELLED)
    }

    @Test
    fun startNewSession_whenVehicleUnplugged_failsAndCreatesNoActiveSession() = runBlocking {
        val dao = FakeChargeDao()
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 0, selectedA = 16, energyKwh = 10.0, voltageV = 228)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            clockMs = { 5_000L }
        )

        val result = repo.startNewSession(targetKwh = 0.0, amperage = 16, targetDurationMinutes = 0)

        assertThat(result.isFailure).isTrue()
        assertThat(dao.getActiveSession()).isNull()
        assertThat(wsCommands.any { it.contains("\nstart\n") }).isFalse()
    }

    @Test
    fun updateTargetKwh_updatesSessionAndSyncsDeadManTimerWithoutSendingStart() = runBlocking {
        val dao = FakeChargeDao()
        dao.insertSession(
            ChargeSessionEntity(
                id = "s1",
                targetKwh = 0.0,
                amperageSetting = 16,
                status = SessionStatus.CHARGING,
                startedAt = 1_000L,
                totalKwhDelivered = 2.0
            )
        )
        val wsCommands = CopyOnWriteArrayList<String>()
        val scraper = buildScraper(wsCommands) {
            wsPayload(statusCode = 2, selectedA = 16, energyKwh = 12.0, voltageV = 230)
        }
        val repo = ChargeRepository(
            scraper = scraper,
            chargeDao = dao,
            scheduleConfigProvider = { Triple(true, "23:00", "07:00") },
            localTimeProvider = { LocalTime.of(12, 0) },
            clockMs = { 5_000L }
        )

        repo.updateTargetKwh("s1", 16.0)
        assertThat(dao.getSessionById("s1")?.targetKwh).isWithin(0.001).of(16.0)
        val timerCommandsAfterArm = wsCommands.filter { it.contains("\ndatetime\n") }
        assertThat(timerCommandsAfterArm).hasSize(1)
        assertThat(wsCommands.any { it.contains("\nstart\n") }).isFalse()

        repo.updateTargetKwh("s1", 0.0)
        assertThat(dao.getSessionById("s1")?.targetKwh).isWithin(0.001).of(0.0)
        val timerCommandsAfterClear = wsCommands.filter { it.contains("\ndatetime\n") }
        assertThat(timerCommandsAfterClear).hasSize(2)
        assertThat(timerCommandsAfterClear.last()).endsWith("\n23:00\n07:00\n")
        assertThat(wsCommands.any { it.contains("\nstart\n") }).isFalse()
    }

    private class FakeChargeDao : ChargeDao {
        private val sessions = linkedMapOf<String, ChargeSessionEntity>()
        private val slices = mutableListOf<ChargeSliceEntity>()
        private val sessionsFlow = MutableStateFlow<List<ChargeSessionEntity>>(emptyList())
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
            return id
        }

        override suspend fun updateSlice(slice: ChargeSliceEntity) {
            val idx = slices.indexOfFirst { it.id == slice.id }
            if (idx >= 0) slices[idx] = slice
        }

        override suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> =
            slices.filter { it.sessionId == sessionId }.sortedBy { it.sliceIndex }

        override fun observeSlicesForSession(sessionId: String): Flow<List<ChargeSliceEntity>> =
            sessionsFlow.map { slices.filter { it.sessionId == sessionId }.sortedBy { it.sliceIndex } }

        override suspend fun getLatestSliceForSession(sessionId: String): ChargeSliceEntity? =
            slices.filter { it.sessionId == sessionId }.maxByOrNull { it.sliceIndex }
    }
}
