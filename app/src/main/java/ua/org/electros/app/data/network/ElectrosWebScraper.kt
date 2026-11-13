package ua.org.electros.app.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.model.TelemetryData
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class ElectrosWebScraper(
    private val cookieStorage: CookieStorage? = null,
    private val authPreferences: AuthPreferences? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply { if (cookieStorage != null) cookieJar(cookieStorage) }
        .addInterceptor { chain ->
            val req = chain.request()
            val next = if (req.header("User-Agent") == null) {
                req.newBuilder().header("User-Agent", DEFAULT_USER_AGENT).build()
            } else {
                req
            }
            chain.proceed(next)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val wsFactory: WebSocket.Factory = client,
    private val wsAckWaitMs: Long = 1_000L,
    private val verifyDelayMs: Long = 3_000L,
    private val serialProvider: () -> String = { authPreferences?.deviceSerial.orEmpty() },
    private val passwordProvider: () -> String = { authPreferences?.internetPassword.orEmpty() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val authBackoffMs: Long = DEFAULT_AUTH_BACKOFF_MS
) {

    private val baseUrl = "https://electros.org.ua"
    private val wsUrl = "wss://electros.org.ua/ws/"
    private val authMutex = Mutex()
    @Volatile private var lastCsrfToken: String? = null
    @Volatile private var authBlockedUntilMs: Long = 0L
    private val authFailures = AtomicInteger(0)

    private fun currentSerial(): String = serialProvider().trim()

    fun resetAuthBackoff() {
        authFailures.set(0)
        authBlockedUntilMs = 0L
    }

    fun clearSession() {
        cookieStorage?.clear()
        lastCsrfToken = null
        resetAuthBackoff()
    }

    suspend fun fetchTelemetry(): Result<TelemetryData> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val wsResult = queryWebSocket("site $serial")
            if (wsResult.isSuccess) {
                parseWebSocketTelemetryOrNull(wsResult.getOrThrow())?.let { telemetry ->
                    return@withContext Result.success(telemetry)
                }
            }
        }

        // Fallback to HTTP protected_page.php if WebSocket query fails or returns invalid telemetry
        fetchTelemetryHttp()
    }

    private suspend fun queryWebSocket(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            withTimeout(5000L) {
                suspendCancellableCoroutine { continuation ->
                    val wsRequest = Request.Builder()
                        .url(wsUrl)
                        .header("Sec-WebSocket-Protocol", "example-protocol")
                        .header("Origin", baseUrl)
                        .build()

                    var ws: WebSocket? = null
                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            webSocket.send(command)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (continuation.isActive) {
                                continuation.resume(Result.success(text))
                            }
                            webSocket.close(1000, "Done")
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(t))
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(IOException("WebSocket closed ($code): $reason")))
                            }
                        }
                    }

                    ws = wsFactory.newWebSocket(wsRequest, listener)
                    continuation.invokeOnCancellation {
                        ws.cancel()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IOException("WebSocket query timed out", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun sendCommandWebSocket(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        var ws: WebSocket? = null
        var cancelled = false
        try {
            withTimeout(5000L) {
                val openSendResult = CompletableDeferred<Result<Unit>>()
                val replyResult = CompletableDeferred<Result<Unit>>()
                val wsRequest = Request.Builder()
                    .url(wsUrl)
                    .header("Sec-WebSocket-Protocol", "example-protocol")
                    .header("Origin", baseUrl)
                    .build()

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        if (webSocket.send(command)) {
                            openSendResult.complete(Result.success(Unit))
                        } else {
                            val err = Result.failure<Unit>(IOException("Failed to send WS frame"))
                            openSendResult.complete(err)
                            replyResult.complete(err)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        replyResult.complete(Result.success(Unit))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        if (!openSendResult.isCompleted) {
                            val err = Result.failure<Unit>(IOException("WebSocket closing before send ($code): $reason"))
                            openSendResult.complete(err)
                            replyResult.complete(err)
                        } else {
                            replyResult.complete(Result.success(Unit))
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!openSendResult.isCompleted) {
                            val err = Result.failure<Unit>(IOException("WebSocket closed before send ($code): $reason"))
                            openSendResult.complete(err)
                            replyResult.complete(err)
                        } else {
                            replyResult.complete(Result.success(Unit))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        val err = Result.failure<Unit>(t)
                        openSendResult.complete(err)
                        replyResult.complete(err)
                    }
                }

                ws = wsFactory.newWebSocket(wsRequest, listener)
                val openStatus = openSendResult.await()
                if (openStatus.isFailure) return@withTimeout openStatus

                if (wsAckWaitMs > 0L) {
                    val reply = withTimeoutOrNull(wsAckWaitMs) { replyResult.await() }
                    if (reply?.isFailure == true) return@withTimeout reply
                }
                Result.success(Unit)
            }
        } catch (e: TimeoutCancellationException) {
            ws?.cancel()
            cancelled = true
            Result.failure(IOException("WebSocket command timed out", e))
        } catch (e: CancellationException) {
            cancelled = true
            ws?.cancel()
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (!cancelled) ws?.close(1000, "Done")
        }
    }

    private suspend fun sendAndVerify(
        command: String,
        verify: (TelemetryData) -> Boolean
    ): Result<Unit> {
        val sendResult = sendCommandWebSocket(command)
        if (sendResult.isFailure) return sendResult
        if (verifyDelayMs > 0L) delay(verifyDelayMs)
        val telemetry = fetchTelemetry().getOrNull()
        return if (telemetry != null && verify(telemetry)) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("Station did not confirm command"))
        }
    }

    suspend fun fetchTelemetryHttp(allowRelogin: Boolean = true): Result<TelemetryData> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/auth/protected_page.php")
                .build()

            val (finalUrl, html) = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                response.request.url.toString() to response.body?.string().orEmpty()
            }

            val isLogin = isLoginResponse(finalUrl, html)

            if (isLogin) {
                if (!allowRelogin) {
                    return@withContext Result.failure(SecurityException("Session not established after login"))
                }
                val reloginResult = reloginWithBackoff()
                if (reloginResult.isFailure) {
                    return@withContext Result.failure(
                        reloginResult.exceptionOrNull() ?: IOException("Login failed")
                    )
                }
                return@withContext fetchTelemetryHttp(allowRelogin = false)
            }

            authMutex.withLock {
                authFailures.set(0)
                authBlockedUntilMs = 0L
            }
            val doc = Jsoup.parse(html)
            extractCsrfToken(doc)

            Result.success(parseTelemetryDocument(doc))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isLoginResponse(finalUrl: String, html: String): Boolean =
        finalUrl.contains("login.php") ||
            finalUrl.contains("main.php") ||
            (html.contains("name=\"password\"") && !html.contains("Станція"))

    private suspend fun reloginWithBackoff(): Result<Unit> = authMutex.withLock {
        if (clockMs() < authBlockedUntilMs) {
            return@withLock Result.failure(
                SecurityException("Authentication temporarily blocked after repeated failures")
            )
        }
        loginUnlocked().also { res ->
            if (res.exceptionOrNull() is IllegalArgumentException) {
                if (authFailures.incrementAndGet() >= 2) {
                    authBlockedUntilMs = clockMs() + authBackoffMs
                }
            } else if (res.isSuccess) {
                authFailures.set(0)
                authBlockedUntilMs = 0L
            }
        }
    }

    suspend fun login(
        serial: String? = null,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        authMutex.withLock {
            if (serial != null || password != null) {
                authFailures.set(0)
                authBlockedUntilMs = 0L
            }
            loginUnlocked(serial, password).also { res ->
                if (res.isSuccess) {
                    authFailures.set(0)
                    authBlockedUntilMs = 0L
                }
            }
        }
    }

    private fun loginUnlocked(
        serial: String? = null,
        password: String? = null
    ): Result<Unit> {
        val targetSerial = (serial ?: serialProvider()).trim()
        val targetPassword = (password ?: passwordProvider()).trim()

        if (targetSerial.isBlank() || targetPassword.isBlank()) {
            return Result.failure(IllegalStateException("Введіть номер пристрою та пароль"))
        }

        return try {
            val formBuilder = FormBody.Builder()
                .add("username", targetSerial)
                .add("password", targetPassword)

            lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

            val request = Request.Builder()
                .url("$baseUrl/auth/login.php")
                .post(formBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.contains("Вхід ElectroS WiFi") || body.contains("Невірний номер")) {
                    Result.failure(IllegalArgumentException("Невірний номер пристрою або пароль"))
                } else if (response.isSuccessful) {
                    val doc = Jsoup.parse(body)
                    extractCsrfToken(doc)
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("Помилка зв'язку з сервером (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startCharge(amperes: Int = 16): Result<Unit> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val idx = amperageToIndex(amperes)
            val wsResult = sendAndVerify("site $serial\nstart\n$idx\n0\n") { it.isChargingActive }
            if (wsResult.isSuccess) return@withContext wsResult
        }
        // Apply amperage via HTTP only on the HTTP fallback path to avoid duplicate WS connections.
        setAmperageHttp(amperes)
        executeAction("start")
    }

    suspend fun stopCharge(
        verifyWithTelemetry: Boolean = true,
        useHttpFallbackOnly: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (!useHttpFallbackOnly && serial.isNotBlank()) {
            val wsCmd = "site $serial\nstop"
            val wsResult = if (verifyWithTelemetry) {
                sendAndVerify(wsCmd) { !it.isChargingActive }
            } else {
                sendCommandWebSocket(wsCmd)
            }
            if (wsResult.isSuccess) return@withContext wsResult
        }
        executeAction("stop")
    }

    suspend fun setAmperage(amperes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val targetAmperes = (if (amperes % 2 != 0) amperes - 1 else amperes).coerceIn(6, 32)
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val idx = amperageToIndex(targetAmperes)
            val wsResult = sendAndVerify("site $serial\nstart\n$idx\n0\n") {
                it.selectedCurrentAmperes == targetAmperes
            }
            if (wsResult.isSuccess) return@withContext wsResult
        }
        setAmperageHttp(targetAmperes)
    }

    private suspend fun setAmperageHttp(amperes: Int): Result<Unit> = postForm(
        listOf(
            "action" to "set_current",
            "current" to "$amperes A",
            "current" to "$amperes",
            "amperage" to "$amperes",
            "amper" to "$amperes",
            "tok" to "$amperes"
        )
    )

    suspend fun setTimer(startTime: String, stopTime: String): Result<Unit> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val nowFormatted = WS_DATE_FORMATTER.format(LocalDateTime.now())
            val wsCmd = "site $serial\ndatetime\n$nowFormatted\n$startTime\n$stopTime\n"
            val wsResult = sendCommandWebSocket(wsCmd)
            if (wsResult.isSuccess) return@withContext wsResult
        }

        postForm(
            listOf(
                "action" to "set_timer",
                "time_start" to startTime,
                "time_stop" to stopTime
            )
        )
    }

    fun amperageToIndex(amperes: Int): Int {
        val evenAmperes = if (amperes % 2 != 0) amperes - 1 else amperes
        return ((32 - evenAmperes) / 2).coerceIn(0, 13)
    }

    private suspend fun executeAction(actionName: String): Result<Unit> = withContext(Dispatchers.IO) {
        postForm(listOf("action" to actionName))
    }

    private suspend fun postForm(
        fields: List<Pair<String, String>>,
        allowRelogin: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val formBuilder = FormBody.Builder()
            fields.forEach { (key, value) -> formBuilder.add(key, value) }
            lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

            val request = Request.Builder()
                .url("$baseUrl/auth/protected_page.php")
                .post(formBuilder.build())
                .build()

            val (finalUrl, html) = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                response.request.url.toString() to response.body?.string().orEmpty()
            }

            if (isLoginResponse(finalUrl, html)) {
                if (!allowRelogin) {
                    return@withContext Result.failure(SecurityException("HTTP command rejected: session not established"))
                }
                val relogin = reloginWithBackoff()
                if (relogin.isFailure) {
                    return@withContext Result.failure(
                        relogin.exceptionOrNull() ?: IOException("Login failed")
                    )
                }
                if (lastCsrfToken == null) {
                    fetchTelemetryHttp(allowRelogin = false)
                }
                return@withContext postForm(fields, allowRelogin = false)
            }

            extractCsrfToken(Jsoup.parse(html))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractCsrfToken(doc: Document) {
        val token = doc.select("input[name=csrf_token]").`val`()
            .ifEmpty { doc.select("input[name=token]").`val`() }
        if (!token.isNullOrBlank()) {
            lastCsrfToken = token
        }
    }

    fun parseWebSocketTelemetryOrNull(data: String): TelemetryData? {
        val lines = data.split("\n")
        if (lines.size < MIN_WS_LINES || lines[8].trim().toIntOrNull() == null) {
            return null
        }
        return parseWebSocketTelemetryLines(lines)
    }

    fun parseWebSocketTelemetry(data: String): TelemetryData =
        parseWebSocketTelemetryLines(data.split("\n"))

    private fun parseWebSocketTelemetryLines(lines: List<String>): TelemetryData {
        val selectedA = lines.getOrNull(7)?.trim()?.toIntOrNull() ?: 16
        val statusCode = lines.getOrNull(8)?.trim()?.toIntOrNull() ?: 7
        val isOnline = statusCode !in listOf(7, 77)

        val statusText = when (statusCode) {
            0 -> "Чекаю підключення до авто"
            1 -> "Підключено. Немає заряду"
            2 -> "Заряджання"
            3 -> "Помилка заряджання"
            4 -> "Заборона заряджання"
            5 -> "Напруга нижче порогового рівня"
            6 -> "Помилка зв'язку №1"
            7, 77 -> "Станція OFFLINE"
            16 -> "Ток витоку виявлено"
            32 -> "Струм перевищив 40 А"
            else -> if (isOnline) "Підключено" else "Станція OFFLINE"
        }

        // Line 9: measured current * 10
        val measuredA = (lines.getOrNull(9)?.trim()?.toDoubleOrNull() ?: 0.0) / 10.0

        // Line 10: grid voltage (null when omitted by telemetry packet instead of fabricating 220V)
        val vCur = lines.getOrNull(10)?.trim()?.toIntOrNull()

        // Line 11: consumed energy in mWh (divide by 1,000,000 to get kWh)
        val energyKwh = (lines.getOrNull(11)?.trim()?.toDoubleOrNull() ?: 0.0) / 1_000_000.0

        // Line 12: power in dW (divide by 10,000 to get kW)
        val powerKw = (lines.getOrNull(12)?.trim()?.toDoubleOrNull() ?: 0.0) / 10_000.0

        // Fault/prohibition codes never count as charging even if a residual current is reported.
        val isFaultStatus = statusCode in WS_FAULT_STATUS_CODES
        val isChargingActive = !isFaultStatus &&
            (statusCode == 2 || (isOnline && (measuredA > 0.5 || powerKw > 0.1)))

        return TelemetryData(
            statusText = statusText,
            isOnline = isOnline,
            voltageCurrent = vCur,
            selectedCurrentAmperes = selectedA,
            measuredCurrentAmperes = measuredA,
            consumedEnergyKwh = energyKwh,
            powerKw = powerKw,
            isChargingActive = isChargingActive
        )
    }

    fun parseTelemetryDocument(doc: Document): TelemetryData {
        val bodyText = doc.body().text()

        // Grid Voltage row: Min / Середня / Поточна / Max
        var vCur: Int? = null
        val vTable = doc.select("table").firstOrNull { table ->
            val txt = table.text().lowercase()
            txt.contains("напруга") && txt.contains("мереж")
        } ?: doc.select("table:contains(Напруга)").firstOrNull()

        if (vTable != null) {
            val dataRows = vTable.select("tr").filter { it.select("td").isNotEmpty() }
            val lastDataRow = dataRows.lastOrNull()
            val cells = lastDataRow?.select("td")
            if (cells != null && cells.isNotEmpty()) {
                val numbers = cells.mapNotNull {
                    it.text().trim().replace(NON_DIGITS_REGEX, "").toIntOrNull()
                }
                vCur = when {
                    numbers.size >= 4 -> numbers[2]
                    numbers.size == 3 -> numbers[1]
                    numbers.isNotEmpty() -> numbers.last()
                    else -> null
                }
            }
        }
        if (vCur == null) {
            val voltMatcher = VOLTAGE_INLINE_REGEX.find(bodyText)
            if (voltMatcher != null) {
                val foundV = voltMatcher.groupValues[1].toIntOrNull()
                if (foundV != null && foundV in 100..450) {
                    vCur = foundV
                }
            }
        }

        // Station status parsing
        var extractedStatus = ""
        val statusEl = doc.select("*:containsOwn(Стан:)").firstOrNull()
            ?: doc.select("*:matchesOwn(Стан\\s*:)").firstOrNull()
        if (statusEl != null) {
            val fullText = statusEl.parent()?.text() ?: statusEl.text()
            extractedStatus = fullText.substringAfter("Стан:").trim()
            extractedStatus = extractedStatus
                .substringBefore("Напруга")
                .substringBefore("Min")
                .substringBefore("Середня")
                .substringBefore("Поточна")
                .substringBefore("Оберіть")
                .substringBefore("Струм")
                .trim()
        }
        if (extractedStatus.isBlank()) {
            val statusMatcher = STATUS_FALLBACK_REGEX.find(bodyText)
            if (statusMatcher != null) {
                extractedStatus = statusMatcher.groupValues[1].trim()
            }
        }

        val isExplicitlyOnline = extractedStatus.contains("Підключено", ignoreCase = true) ||
                extractedStatus.contains("Заряд", ignoreCase = true) ||
                extractedStatus.contains("Очікування", ignoreCase = true) ||
                extractedStatus.contains("ONLINE", ignoreCase = true)

        val isExplicitlyOffline = extractedStatus.contains("OFFLINE", ignoreCase = true) ||
                extractedStatus.contains("Немає зв'язку", ignoreCase = true) ||
                extractedStatus.contains("Зв'язок відсутній", ignoreCase = true) ||
                extractedStatus.contains("Станція OFFLINE", ignoreCase = true) ||
                extractedStatus.contains("Вимкнен", ignoreCase = true)

        val isOnline = when {
            isExplicitlyOffline -> false
            isExplicitlyOnline -> true
            bodyText.contains("Станція OFFLINE", ignoreCase = true) -> false
            (vCur ?: 0) > 50 -> true
            bodyText.contains("Напруга мережі", ignoreCase = true) || bodyText.contains("Напруга Мережі") -> true
            else -> false
        }

        val statusText = when {
            extractedStatus.isNotBlank() -> extractedStatus
            isOnline -> "Підключено"
            else -> "Станція OFFLINE"
        }

        // Selected Amperes dropdown
        var selectedA = 16
        val currentSelect = doc.select("select").firstOrNull {
            it.parent()?.text()?.contains("струм", ignoreCase = true) == true ||
            it.attr("name").contains("curr", ignoreCase = true) ||
            it.attr("name").contains("amp", ignoreCase = true) ||
            it.attr("name").contains("tok", ignoreCase = true)
        } ?: doc.select("select").firstOrNull()

        val selectedOpt = currentSelect?.select("option[selected]")?.firstOrNull()
        if (selectedOpt != null) {
            val num = selectedOpt.text().replace(NON_DIGITS_REGEX, "").toIntOrNull()
                ?: selectedOpt.`val`().replace(NON_DIGITS_REGEX, "").toIntOrNull()
            if (num != null && num in 6..32) {
                selectedA = num
            }
        } else {
            val textMatch = SELECTED_CURRENT_REGEX.find(bodyText)
            if (textMatch != null) {
                val num = textMatch.groupValues[1].toIntOrNull()
                if (num != null && num in 6..32) selectedA = num
            }
        }

        // Measured Current
        var measuredA = 0.0
        val currentMatcher = MEASURED_CURRENT_REGEX.find(bodyText)
        if (currentMatcher != null) {
            measuredA = parseDecimal(currentMatcher.groupValues[1]) ?: 0.0
        }

        // Consumed Energy
        var energyKwh = 0.0
        val energyRow = doc.select("tr:has(td:contains(Спожита енергія))").first()
        if (energyRow != null) {
            val m = ENERGY_KWH_REGEX.find(energyRow.text())
            if (m != null) energyKwh = parseDecimal(m.groupValues[1]) ?: 0.0
        } else {
            val energyEl = doc.select(":containsOwn(кВт*год)").first()
            if (energyEl != null) {
                val raw = energyEl.parent()?.text().orEmpty()
                val m = ENERGY_KWH_REGEX.find(raw)
                if (m != null) energyKwh = parseDecimal(m.groupValues[1]) ?: 0.0
            }
        }

        // Power kW (strictly distinguished from кВт*год)
        var powerKw = 0.0
        val powerRow = doc.select("tr:has(td:contains(Потужність))").first()
        if (powerRow != null) {
            val m = POWER_KW_REGEX.find(powerRow.text())
            if (m != null) powerKw = parseDecimal(m.groupValues[1]) ?: 0.0
        } else {
            val powerMatch = POWER_KW_REGEX.find(bodyText)
            if (powerMatch != null) {
                powerKw = parseDecimal(powerMatch.groupValues[1]) ?: 0.0
            }
        }

        val isStatusCharging = statusText.contains("Заряд", ignoreCase = true) &&
                !statusText.contains("Немає заряду", ignoreCase = true) &&
                !statusText.contains("Помилка", ignoreCase = true) &&
                !statusText.contains("Заборон", ignoreCase = true)
        val isFaultStatus = HTTP_FAULT_STATUS_MARKERS.any { statusText.contains(it, ignoreCase = true) }
        val isChargingActive = isOnline && !isFaultStatus &&
                (measuredA > 0.5 || powerKw > 0.1 || isStatusCharging)

        return TelemetryData(
            statusText = statusText,
            isOnline = isOnline,
            voltageCurrent = vCur,
            selectedCurrentAmperes = selectedA,
            measuredCurrentAmperes = measuredA,
            consumedEnergyKwh = energyKwh,
            powerKw = powerKw,
            isChargingActive = isChargingActive
        )
    }

    private fun parseDecimal(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()

    companion object {
        private const val MIN_WS_LINES = 14
        /** 3 charge error, 4 charging forbidden, 5 low voltage, 6 comm error, 16 leakage, 32 overcurrent. */
        private val WS_FAULT_STATUS_CODES = setOf(3, 4, 5, 6, 16, 32)
        private val HTTP_FAULT_STATUS_MARKERS = listOf(
            "Заборон", "Помилка", "Напруга нижче", "витоку", "перевищив"
        )
        private const val DEFAULT_AUTH_BACKOFF_MS = 15 * 60_000L
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36"

        private val WS_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM.yyyy'T'HH:mm:ss", Locale.US)

        private val NON_DIGITS_REGEX = Regex("[^0-9]")
        private val VOLTAGE_INLINE_REGEX = Regex("([1-9][0-9]{2})\\s*[ВVv]")
        private val STATUS_FALLBACK_REGEX =
            Regex("Стан\\s*:\\s*([A-Za-zА-Яа-яІіЇїЄє0-9\\s.]+?)(?=Напруга|Min|Середня|Поточна|Оберіть|Струм|\\n|$)")
        private val SELECTED_CURRENT_REGEX = Regex("Оберіть струм[^0-9]*([0-9]+)\\s*A?")
        private val MEASURED_CURRENT_REGEX = Regex("Струм,\\s*виміряний:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*A")
        private val ENERGY_KWH_REGEX = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*кВт\\*год")
        private val POWER_KW_REGEX = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*кВт(?!\\*год)")
    }
}
