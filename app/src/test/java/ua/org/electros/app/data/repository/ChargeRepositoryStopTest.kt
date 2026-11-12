package ua.org.electros.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Test
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.network.ElectrosWebScraper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ChargeRepositoryStopTest {

    @Test
    fun markSessionTargetReached_whenStillCharging_setsStopPendingAndReturnsFalse() = runTest {
        val dao = FakeChargeDao()
        seedActiveSession(dao, status = SessionStatus.CHARGING, targetKwh = 10.0)
        val repo = ChargeRepository(
            scraper = scraperReturning(CHARGING_HTML),
            chargeDao = dao
        )

        val stopped = repo.markSessionTargetReached(
            sessionId = "s1",
            lastCounter = 12.0,
            confirmDelayMs = 0L,
            retryDelayMs = 0L
        )

        assertThat(stopped).isFalse()
        assertThat(dao.getSessionById("s1")?.status).isEqualTo(SessionStatus.STOP_PENDING)
        assertThat(dao.getSessionById("s1")?.completedAt).isNull()
        assertThat(dao.getLatestSliceForSession("s1")?.endedAt).isNull()
    }

    @Test
    fun markSessionTargetReached_whenStationOffline_keepsStopPendingAndReturnsFalse() = runTest {
        val dao = FakeChargeDao()
        seedActiveSession(dao, status = SessionStatus.CHARGING, targetKwh = 10.0)
        val repo = ChargeRepository(
            scraper = scraperReturning(OFFLINE_HTML),
            chargeDao = dao
        )

        val stopped = repo.markSessionTargetReached(
            sessionId = "s1",
            lastCounter = 12.0,
            confirmDelayMs = 0L,
            retryDelayMs = 0L
        )

        assertThat(stopped).isFalse()
        assertThat(dao.getSessionById("s1")?.status).isEqualTo(SessionStatus.STOP_PENDING)
        assertThat(dao.getLatestSliceForSession("s1")?.endedAt).isNull()
    }

    @Test
    fun markSessionTargetReached_whenStationConfirmsIdle_finalizesTargetReachedAndReturnsTrue() = runTest {
        val dao = FakeChargeDao()
        seedActiveSession(dao, status = SessionStatus.CHARGING, targetKwh = 10.0, startCounter = 2.0)
        val repo = ChargeRepository(
            scraper = scraperReturning(CONNECTED_IDLE_HTML),
            chargeDao = dao
        )

        val stopped = repo.markSessionTargetReached(
            sessionId = "s1",
            lastCounter = 12.5,
            confirmDelayMs = 0L,
            retryDelayMs = 0L
        )

        assertThat(stopped).isTrue()
        val session = dao.getSessionById("s1")!!
        assertThat(session.status).isEqualTo(SessionStatus.TARGET_REACHED)
        assertThat(session.completedAt).isNotNull()
        assertThat(session.totalKwhDelivered).isWithin(0.01).of(10.5)
        val slice = dao.getLatestSliceForSession("s1")!!
        assertThat(slice.terminationReason).isEqualTo(SliceEndReason.TARGET_REACHED)
        assertThat(slice.endedAt).isNotNull()
    }

    @Test
    fun stopActiveSession_whenStillCharging_setsStopPendingInsteadOfCancelling() = runTest {
        val dao = FakeChargeDao()
        seedActiveSession(dao, status = SessionStatus.CHARGING, targetKwh = 0.0)
        val repo = ChargeRepository(
            scraper = scraperReturning(CHARGING_HTML),
            chargeDao = dao
        )

        val res = repo.stopActiveSession(
            sessionId = "s1",
            confirmDelayMs = 0L,
            retryDelayMs = 0L
        )

        assertThat(res.isFailure).isTrue()
        assertThat(dao.getSessionById("s1")?.status).isEqualTo(SessionStatus.STOP_PENDING)
        assertThat(dao.getLatestSliceForSession("s1")?.endedAt).isNull()
    }

    @Test
    fun stopActiveSession_whenConfirmedStoppedOnStation_cancelsAndFinalizesSlice() = runTest {
        val dao = FakeChargeDao()
        seedActiveSession(dao, status = SessionStatus.CHARGING, targetKwh = 0.0, startCounter = 5.0)
        val repo = ChargeRepository(
            scraper = scraperReturning(CONNECTED_IDLE_HTML),
            chargeDao = dao
        )

        val res = repo.stopActiveSession(
            sessionId = "s1",
            confirmDelayMs = 0L,
            retryDelayMs = 0L
        )

        assertThat(res.isSuccess).isTrue()
        assertThat(dao.getSessionById("s1")?.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(dao.getLatestSliceForSession("s1")?.terminationReason).isEqualTo(SliceEndReason.MANUAL_STOP)
    }

    @Test
    fun restoreChargingFromExistingSlice_withoutRegression_opensContinuationSliceAndPreservesTotal() = runTest {
        val dao = FakeChargeDao()
        seedActiveSession(dao, status = SessionStatus.CHARGING, targetKwh = 10.0, startCounter = 100.0)
        val repo = ChargeRepository(
            scraper = scraperReturning(CHARGING_HTML),
            chargeDao = dao
        )

        repo.handleOutageDetected("s1", 105.0)
        val closedFirstSlice = dao.getSlicesForSession("s1").single()
        assertThat(closedFirstSlice.endedAt).isNotNull()
        assertThat(closedFirstSlice.finalizedKwh).isWithin(0.01).of(5.0)

        repo.restoreChargingFromExistingSlice("s1", currentCounter = 106.5)

        val session = dao.getSessionById("s1")!!
        val slices = dao.getSlicesForSession("s1")
        assertThat(session.status).isEqualTo(SessionStatus.CHARGING)
        assertThat(slices).hasSize(2)
        assertThat(slices[1].endedAt).isNull()
        assertThat(slices[1].stationCounterStartKwh).isWithin(0.01).of(105.0)

        repo.finalizeSessionStopped("s1", 110.0, SliceEndReason.TARGET_REACHED, SessionStatus.TARGET_REACHED)
        assertThat(dao.getSessionById("s1")?.totalKwhDelivered).isWithin(0.01).of(10.0)
    }

    @Test
    fun restoreChargingFromExistingSlice_withCounterRegression_anchorsAtNewCounter() = runTest {
        val dao = FakeChargeDao()
        seedActiveSession(dao, status = SessionStatus.CHARGING, targetKwh = 10.0, startCounter = 100.0)
        val repo = ChargeRepository(
            scraper = scraperReturning(CHARGING_HTML),
            chargeDao = dao
        )

        repo.handleOutageDetected("s1", 105.0)
        repo.restoreChargingFromExistingSlice("s1", currentCounter = 0.4)

        val slices = dao.getSlicesForSession("s1")
        assertThat(slices).hasSize(2)
        assertThat(slices[0].finalizedKwh).isWithin(0.01).of(5.0)
        assertThat(slices[1].stationCounterStartKwh).isWithin(0.01).of(0.4)

        repo.finalizeSessionStopped("s1", 2.4, SliceEndReason.TARGET_REACHED, SessionStatus.TARGET_REACHED)
        assertThat(dao.getSessionById("s1")?.totalKwhDelivered).isWithin(0.01).of(7.0)
    }

    @Test
    fun stopAndConfirm_verifiesOnlyInRepositoryAndFallsBackToHttpOnNextAttempt() = runTest {
        val wsMessages = CopyOnWriteArrayList<String>()
        val httpStopTriggered = AtomicBoolean(false)
        val wsFactory = WebSocket.Factory { request, listener ->
            lateinit var ws: WebSocket
            ws = object : WebSocket {
                override fun request(): Request = request
                override fun queueSize(): Long = 0L
                override fun send(text: String): Boolean {
                    wsMessages += text
                    Thread {
                        if (text.contains("\nstop")) {
                            listener.onMessage(ws, "ok")
                        } else {
                            val statusCode = if (httpStopTriggered.get()) 1 else 2
                            val measured = if (httpStopTriggered.get()) "0" else "155"
                            val power = if (httpStopTriggered.get()) "0" else "35000"
                            val payload = listOf(
                                "8800Lt", "0", "0", "0", "0", "0", "0",
                                "16", "$statusCode", measured, "225", "12500000", power, "2",
                                "0", "0", "230", "218", "3", "1", "224", "2"
                            ).joinToString("\n")
                            listener.onMessage(ws, payload)
                        }
                    }.start()
                    return true
                }
                override fun send(bytes: okio.ByteString): Boolean = false
                override fun close(code: Int, reason: String?): Boolean = true
                override fun cancel() {}
            }
            val wsResp = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()
            Thread { listener.onOpen(ws, wsResp) }.start()
            ws
        }

        val httpStopCount = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.method == "POST") {
                    httpStopTriggered.set(true)
                    httpStopCount += req.url.toString()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("OK".toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()

        val scraper = ElectrosWebScraper(
            client = client,
            wsFactory = wsFactory,
            wsAckWaitMs = 200L,
            verifyDelayMs = 5_000L,
            serialProvider = { "12345" }
        )
        val repo = ChargeRepository(scraper = scraper, chargeDao = FakeChargeDao())

        val stopped = repo.stopAndConfirm(
            maxAttempts = 3,
            allowOfflineConfirmation = false,
            confirmDelayMs = 0L,
            retryDelayMs = 0L
        )

        assertThat(stopped).isTrue()
        // Attempt 0: 1 WS stop + 1 WS telemetry poll; Attempt 1: HTTP stop fallback + 1 WS telemetry poll
        assertThat(wsMessages).containsExactly("site 12345\nstop", "site 12345", "site 12345").inOrder()
        assertThat(httpStopCount).hasSize(1)
    }

    private suspend fun seedActiveSession(
        dao: FakeChargeDao,
        status: SessionStatus,
        targetKwh: Double,
        startCounter: Double = 2.0
    ) {
        dao.insertSession(
            ChargeSessionEntity(
                id = "s1",
                targetKwh = targetKwh,
                amperageSetting = 16,
                status = status,
                startedAt = 1_000L
            )
        )
        dao.startNewSlice("s1", startCounter, startTime = 1_000L)
    }

    private fun scraperReturning(telemetryHtml: String): ElectrosWebScraper {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val bodyText = if (url.contains("protected_page.php")) telemetryHtml else "OK"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyText.toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()
        return ElectrosWebScraper(client = client)
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

    companion object {
        private val CHARGING_HTML = """
            <!DOCTYPE html><html><body>
            <div>Стан: <span>Заряджання</span></div>
            <table><caption>Напруга мережі, В:</caption>
            <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
            <tr><td>210</td><td>220</td><td>224</td><td>230</td></tr></table>
            <div>Оберіть струм заряджання:<select name="current"><option value="16" selected>16 A</option></select></div>
            <div>Струм, виміряний: 15.8 A</div>
            <table>
            <tr><td>Спожита енергія</td><td><span>12.0 кВт*год</span></td></tr>
            <tr><td>Потужність</td><td><span>3.5 кВт</span></td></tr>
            </table></body></html>
        """.trimIndent()

        private val CONNECTED_IDLE_HTML = """
            <!DOCTYPE html><html><body>
            <div>Стан: <span>Підключено. Немає заряду</span></div>
            <table><caption>Напруга мережі, В:</caption>
            <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
            <tr><td>215</td><td>222</td><td>228</td><td>232</td></tr></table>
            <div>Оберіть струм заряджання:<select name="current"><option value="16" selected>16 A</option></select></div>
            <div>Струм, виміряний: 0.0 A</div>
            <table>
            <tr><td>Спожита енергія</td><td><span>12.5 кВт*год</span></td></tr>
            <tr><td>Потужність</td><td><span>0.0 кВт</span></td></tr>
            </table></body></html>
        """.trimIndent()

        private val OFFLINE_HTML = """
            <!DOCTYPE html><html><body>
            <div>Стан: <span>Станція OFFLINE</span></div>
            <table><caption>Напруга мережі, В:</caption>
            <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
            <tr><td>0</td><td>0</td><td>0</td><td>0</td></tr></table>
            <div>Струм, виміряний: 0.0 A</div>
            <table>
            <tr><td>Спожита енергія</td><td><span>12.0 кВт*год</span></td></tr>
            <tr><td>Потужність</td><td><span>0.0 кВт</span></td></tr>
            </table></body></html>
        """.trimIndent()
    }
}

