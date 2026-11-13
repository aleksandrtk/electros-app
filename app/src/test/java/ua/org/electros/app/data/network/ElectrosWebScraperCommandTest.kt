package ua.org.electros.app.data.network

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ElectrosWebScraperCommandTest {

    private class FakeWebSocket(
        private val req: Request,
        private val onSendText: (String, FakeWebSocket) -> Unit
    ) : WebSocket {
        val sentMessages = CopyOnWriteArrayList<String>()
        val closeCalled = AtomicBoolean(false)
        val cancelCalled = AtomicBoolean(false)
        @Volatile var closeCode: Int? = null

        override fun request(): Request = req
        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            sentMessages.add(text)
            onSendText(text, this)
            return true
        }

        override fun send(bytes: ByteString): Boolean = false

        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            closeCalled.set(true)
            return true
        }

        override fun cancel() {
            cancelCalled.set(true)
        }
    }

    private fun dummyWsResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    private fun wsTelemetryPayload(
        statusCode: Int,
        selectedA: Int = 16,
        measuredA: Double = 0.0,
        powerKw: Double = 0.0
    ): String {
        val lines = MutableList(22) { "0" }
        lines[7] = selectedA.toString()
        lines[8] = statusCode.toString()
        lines[9] = (measuredA * 10.0).toInt().toString()
        lines[10] = "230"
        lines[11] = "1500000"
        lines[12] = (powerKw * 10_000.0).toInt().toString()
        lines[13] = "2"
        lines[16] = "235"
        lines[17] = "225"
        lines[20] = "230"
        return lines.joinToString("\n")
    }

    private data class RecordedHttpPost(
        val url: String,
        val params: List<Pair<String, String>>
    )

    private fun buildRecordingHttpClient(
        recordedPosts: MutableList<RecordedHttpPost>,
        httpCode: Int = 200
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val form = req.body as? FormBody
            val params = if (form != null) {
                (0 until form.size).map { idx -> form.name(idx) to form.value(idx) }
            } else {
                emptyList()
            }
            recordedPosts.add(RecordedHttpPost(req.url.toString(), params))
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(httpCode)
                .message(if (httpCode in 200..299) "OK" else "Error")
                .body("<html><body></body></html>".toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    private fun createScriptedWsFactory(
        onSocketSend: (text: String, ws: FakeWebSocket, listener: WebSocketListener) -> Unit
    ): Pair<WebSocket.Factory, CopyOnWriteArrayList<FakeWebSocket>> {
        val sockets = CopyOnWriteArrayList<FakeWebSocket>()
        val factory = WebSocket.Factory { request, listener ->
            lateinit var ws: FakeWebSocket
            ws = FakeWebSocket(request) { text, fakeWs ->
                onSocketSend(text, fakeWs, listener)
            }
            sockets.add(ws)
            Thread {
                listener.onOpen(ws, dummyWsResponse(request))
            }.start()
            ws
        }
        return factory to sockets
    }

    @Test
    fun `sendCommandWebSocket does not close in onOpen before onMessage and resolves on reply`() = runBlocking {
        val closeCalledBeforeReply = AtomicBoolean(true)
        val (wsFactory, sockets) = createScriptedWsFactory { _, ws, listener ->
            Thread {
                Thread.sleep(60L)
                closeCalledBeforeReply.set(ws.closeCalled.get())
                listener.onMessage(ws, "ok")
            }.start()
        }

        val httpPosts = CopyOnWriteArrayList<RecordedHttpPost>()
        val scraper = ElectrosWebScraper(
            client = buildRecordingHttpClient(httpPosts),
            wsFactory = wsFactory,
            wsAckWaitMs = 2_000L,
            verifyDelayMs = 0L,
            serialProvider = { "12345" }
        )

        val startNs = System.nanoTime()
        val result = scraper.setTimer("02:00", "06:00")
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue(result.isSuccess)
        assertFalse("WebSocket must stay open until server reply arrives", closeCalledBeforeReply.get())
        assertTrue("Expected early completion on onMessage (<1000ms), took ${elapsedMs}ms", elapsedMs < 1_000L)
        assertEquals(1, sockets.size)
        assertTrue(sockets[0].closeCalled.get())
        assertEquals(1000, sockets[0].closeCode)
        assertTrue(httpPosts.isEmpty())
    }

    @Test
    fun `stopCharge falls back to HTTP when telemetry stays charging after WS command`() = runBlocking {
        val (wsFactory, sockets) = createScriptedWsFactory { text, ws, listener ->
            Thread {
                if (text.contains("\nstop")) {
                    listener.onMessage(ws, "ok")
                } else {
                    // Telemetry still shows active charging after stop command
                    listener.onMessage(ws, wsTelemetryPayload(statusCode = 2, selectedA = 16, measuredA = 15.5, powerKw = 3.5))
                }
            }.start()
        }

        val httpPosts = CopyOnWriteArrayList<RecordedHttpPost>()
        val scraper = ElectrosWebScraper(
            client = buildRecordingHttpClient(httpPosts),
            wsFactory = wsFactory,
            wsAckWaitMs = 200L,
            verifyDelayMs = 0L,
            serialProvider = { "12345" }
        )

        val result = scraper.stopCharge()

        assertTrue(result.isSuccess)
        assertEquals(2, sockets.size)
        assertEquals("site 12345\nstop", sockets[0].sentMessages.single())
        assertEquals("site 12345", sockets[1].sentMessages.single())
        assertEquals(1, httpPosts.size)
        assertTrue(httpPosts[0].params.contains("action" to "stop"))
    }

    @Test
    fun `startCharge falls back to HTTP when telemetry stays idle after WS command`() = runBlocking {
        val (wsFactory, sockets) = createScriptedWsFactory { text, ws, listener ->
            Thread {
                if (text.contains("\nstart\n")) {
                    listener.onMessage(ws, "ok")
                } else {
                    // Telemetry remains idle (status 1)
                    listener.onMessage(ws, wsTelemetryPayload(statusCode = 1, selectedA = 16, measuredA = 0.0, powerKw = 0.0))
                }
            }.start()
        }

        val httpPosts = CopyOnWriteArrayList<RecordedHttpPost>()
        val scraper = ElectrosWebScraper(
            client = buildRecordingHttpClient(httpPosts),
            wsFactory = wsFactory,
            wsAckWaitMs = 200L,
            verifyDelayMs = 0L,
            serialProvider = { "12345" }
        )

        val result = scraper.startCharge(amperes = 16)

        assertTrue(result.isSuccess)
        assertEquals(2, sockets.size)
        assertEquals("site 12345\nstart\n8\n0\n", sockets[0].sentMessages.single())
        assertEquals(2, httpPosts.size)
        assertTrue(httpPosts[0].params.contains("action" to "set_current"))
        assertTrue(httpPosts[1].params.contains("action" to "start"))
    }

    @Test
    fun `setAmperage succeeds via WS without HTTP fallback when telemetry confirms target amperage`() = runBlocking {
        val queryCount = AtomicInteger(0)
        val (wsFactory, sockets) = createScriptedWsFactory { text, ws, listener ->
            Thread {
                if (text.contains("\nstart\n")) {
                    listener.onMessage(ws, "ok")
                } else {
                    queryCount.incrementAndGet()
                    listener.onMessage(ws, wsTelemetryPayload(statusCode = 2, selectedA = 10, measuredA = 9.8, powerKw = 2.2))
                }
            }.start()
        }

        val httpPosts = CopyOnWriteArrayList<RecordedHttpPost>()
        val scraper = ElectrosWebScraper(
            client = buildRecordingHttpClient(httpPosts),
            wsFactory = wsFactory,
            wsAckWaitMs = 200L,
            verifyDelayMs = 0L,
            serialProvider = { "12345" }
        )

        val result = scraper.setAmperage(10)

        assertTrue(result.isSuccess)
        assertEquals(1, queryCount.get())
        assertEquals(2, sockets.size)
        assertEquals("site 12345\nstart\n11\n0\n", sockets[0].sentMessages.single())
        assertTrue("HTTP fallback must not execute when WS telemetry confirms 10A", httpPosts.isEmpty())
    }

    @Test
    fun `setAmperage falls back to HTTP when telemetry reports different amperage`() = runBlocking {
        val (wsFactory, _) = createScriptedWsFactory { text, ws, listener ->
            Thread {
                if (text.contains("\nstart\n")) {
                    listener.onMessage(ws, "ok")
                } else {
                    // Station stayed on 16A instead of switching to 10A
                    listener.onMessage(ws, wsTelemetryPayload(statusCode = 2, selectedA = 16, measuredA = 15.8, powerKw = 3.6))
                }
            }.start()
        }

        val httpPosts = CopyOnWriteArrayList<RecordedHttpPost>()
        val scraper = ElectrosWebScraper(
            client = buildRecordingHttpClient(httpPosts),
            wsFactory = wsFactory,
            wsAckWaitMs = 200L,
            verifyDelayMs = 0L,
            serialProvider = { "12345" }
        )

        val result = scraper.setAmperage(10)

        assertTrue(result.isSuccess)
        assertEquals(1, httpPosts.size)
        assertTrue(httpPosts[0].params.contains("action" to "set_current"))
        assertTrue(httpPosts[0].params.contains("amperage" to "10"))
    }

    @Test
    fun `http fallback relogins fetches csrf token and retries post when redirected to login`() = runBlocking {
        val loggedIn = AtomicBoolean(false)
        val recordedPosts = CopyOnWriteArrayList<RecordedHttpPost>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val form = req.body as? FormBody
                val params = if (form != null) {
                    (0 until form.size).map { idx -> form.name(idx) to form.value(idx) }
                } else {
                    emptyList()
                }
                if (req.method == "POST") {
                    recordedPosts.add(RecordedHttpPost(req.url.toString(), params))
                }
                val html = when {
                    req.url.encodedPath.contains("login.php") -> {
                        loggedIn.set(true)
                        """<html><body><input type="hidden" name="csrf_token" value="tok-42"/></body></html>"""
                    }
                    !loggedIn.get() -> {
                        """<html><body><input type="password" name="password"/></body></html>"""
                    }
                    else -> {
                        """<html><body>Станція ONLINE</body></html>"""
                    }
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            })
            .build()

        val scraper = ElectrosWebScraper(
            client = client,
            serialProvider = { "12345" },
            passwordProvider = { "secret" }
        )

        val result = scraper.stopCharge(verifyWithTelemetry = false, useHttpFallbackOnly = true)

        assertTrue(result.isSuccess)
        assertEquals(3, recordedPosts.size)
        assertTrue(recordedPosts[0].params.contains("action" to "stop"))
        assertTrue(recordedPosts[1].url.contains("login.php"))
        assertTrue(recordedPosts[2].params.contains("action" to "stop"))
        assertTrue(recordedPosts[2].params.contains("csrf_token" to "tok-42"))
    }

    @Test
    fun `http fallback fails instead of reporting false success when login redirect persists`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val html = if (req.url.encodedPath.contains("login.php")) {
                    """<html><body>Вхід ElectroS WiFi — Невірний номер</body></html>"""
                } else {
                    """<html><body><input type="password" name="password"/></body></html>"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            })
            .build()

        val scraper = ElectrosWebScraper(
            client = client,
            serialProvider = { "12345" },
            passwordProvider = { "wrong" }
        )

        val result = scraper.stopCharge(verifyWithTelemetry = false, useHttpFallbackOnly = true)

        assertTrue(result.isFailure)
    }
}

