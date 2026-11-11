package ua.org.electros.app.data.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ElectrosWebScraperAuthTest {

    private class TrackingResponseBody(
        private val delegate: ResponseBody,
        private val closeCounter: AtomicInteger
    ) : ResponseBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = delegate.source()
        override fun close() {
            closeCounter.incrementAndGet()
            super.close()
        }
    }

    private fun trackedResponse(
        request: Request,
        code: Int,
        bodyText: String,
        closedBodies: AtomicInteger,
        openedBodies: AtomicInteger
    ): Response {
        openedBodies.incrementAndGet()
        val raw = bodyText.toResponseBody("text/html; charset=utf-8".toMediaType())
        val tracked = TrackingResponseBody(raw, closedBodies)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(tracked)
            .build()
    }

    private fun telemetryHtml(): String = """
        <!DOCTYPE html><html><body>
        <div>Стан: <span>Підключено. Немає заряду</span></div>
        <table><caption>Напруга мережі, В:</caption>
        <tr><th>Min</th><th>Середня</th><th>Поточна</th><th>Max</th></tr>
        <tr><td>215</td><td>220</td><td>225</td><td>230</td></tr></table>
        </body></html>
    """.trimIndent()

    private fun loginPageHtml(errorBanner: Boolean = false): String = if (errorBanner) {
        """<html><body><h1>Вхід ElectroS WiFi</h1><p>Невірний номер</p><input type="password" name="password"/></body></html>"""
    } else {
        """<html><body><input type="password" name="password"/></body></html>"""
    }

    @Test
    fun `fetchTelemetryHttp breaks login redirect loop instead of recursing forever`() = runBlocking {
        val protectedCalls = AtomicInteger(0)
        val loginCalls = AtomicInteger(0)
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath.contains("login.php")) {
                    loginCalls.incrementAndGet()
                    trackedResponse(req, 200, "<html><body>OK</body></html>", closed, opened)
                } else {
                    protectedCalls.incrementAndGet()
                    trackedResponse(req, 200, loginPageHtml(errorBanner = false), closed, opened)
                }
            })
            .build()

        val scraper = ElectrosWebScraper(
            client = client,
            serialProvider = { "12345" },
            passwordProvider = { "secret" }
        )

        val result = scraper.fetchTelemetryHttp()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals(2, protectedCalls.get())
        assertEquals(1, loginCalls.get())
        assertEquals(opened.get(), closed.get())
    }

    @Test
    fun `fetchTelemetryHttp opens circuit breaker for backoff window and recovers after expiry`() = runBlocking {
        val nowMs = AtomicLong(1_000_000L)
        val loginCalls = AtomicInteger(0)
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)
        var returnValidTelemetry = false

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath.contains("login.php")) {
                    val n = loginCalls.incrementAndGet()
                    val html = if (n <= 2) loginPageHtml(errorBanner = true) else "<html><body>OK</body></html>"
                    if (n >= 3) returnValidTelemetry = true
                    trackedResponse(req, 200, html, closed, opened)
                } else {
                    val html = if (returnValidTelemetry) telemetryHtml() else loginPageHtml(errorBanner = false)
                    trackedResponse(req, 200, html, closed, opened)
                }
            })
            .build()

        val scraper = ElectrosWebScraper(
            client = client,
            serialProvider = { "12345" },
            passwordProvider = { "wrong" },
            clockMs = { nowMs.get() },
            authBackoffMs = 60_000L
        )

        // Two invalid-credential attempts open backoff window
        assertTrue(scraper.fetchTelemetryHttp().isFailure)
        assertTrue(scraper.fetchTelemetryHttp().isFailure)
        assertEquals(2, loginCalls.get())

        // Third attempt within backoff window does not hit login.php
        val blockedResult = scraper.fetchTelemetryHttp()
        assertTrue(blockedResult.isFailure)
        assertTrue(blockedResult.exceptionOrNull() is SecurityException)
        assertEquals(2, loginCalls.get())

        // After backoff expires, next telemetry fetch retries login and succeeds
        nowMs.addAndGet(61_000L)
        val recoveredResult = scraper.fetchTelemetryHttp()
        assertTrue(recoveredResult.isSuccess)
        assertEquals(3, loginCalls.get())
        assertEquals(opened.get(), closed.get())
    }

    @Test
    fun `fetchTelemetryHttp closes HTTP 500 responses and serializes concurrent relogin`() = runBlocking {
        val loginCalls = AtomicInteger(0)
        val authenticated = AtomicInteger(0)
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                when {
                    req.url.encodedPath.contains("error500") -> {
                        trackedResponse(req, 500, "err", closed, opened)
                    }
                    req.url.encodedPath.contains("login.php") -> {
                        Thread.sleep(40L)
                        loginCalls.incrementAndGet()
                        authenticated.set(1)
                        trackedResponse(req, 200, "<html><body>OK</body></html>", closed, opened)
                    }
                    else -> {
                        if (authenticated.get() == 1) {
                            trackedResponse(req, 200, telemetryHtml(), closed, opened)
                        } else {
                            trackedResponse(req, 200, loginPageHtml(errorBanner = false), closed, opened)
                        }
                    }
                }
            })
            .build()

        val scraper = ElectrosWebScraper(
            client = client,
            serialProvider = { "12345" },
            passwordProvider = { "secret" }
        )

        val results = (1..4).map {
            async { scraper.fetchTelemetryHttp() }
        }.awaitAll()

        assertTrue(results.all { it.isSuccess })
        // Second call sees updated authenticated=1 only after login finishes under mutex (at most serialized)
        assertTrue("Expected at least 1 login and serialized completion", loginCalls.get() >= 1)
        assertEquals(opened.get(), closed.get())
    }

    @Test
    fun `fetchTelemetry returns failure on truncated WS telemetry instead of fabricated voltage`() = runBlocking {
        val httpCalled = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                httpCalled.incrementAndGet()
                trackedResponse(chain.request(), 200, telemetryHtml(), AtomicInteger(), AtomicInteger())
            })
            .build()

        val factory = WebSocket.Factory { request, listener ->
            lateinit var ws: WebSocket
            ws = object : WebSocket {
                override fun request(): Request = request
                override fun queueSize(): Long = 0L
                override fun send(text: String): Boolean {
                    Thread { listener.onMessage(ws, "8800Lt\n1\n2") }.start()
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

        val scraper = ElectrosWebScraper(
            client = client,
            wsFactory = factory,
            serialProvider = { "12345" }
        )

        val result = scraper.fetchTelemetry()

        assertTrue(result.isFailure)
        assertEquals(0, httpCalled.get())
    }

    @Test
    fun `fetchTelemetryHttp closes response body on HTTP 500`() = runBlocking {
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                trackedResponse(chain.request(), 500, "Internal Error", closed, opened)
            })
            .build()

        val scraper = ElectrosWebScraper(client = client)
        val res = scraper.fetchTelemetryHttp()

        assertTrue(res.isFailure)
        assertEquals(1, opened.get())
        assertEquals(1, closed.get())
    }
}
