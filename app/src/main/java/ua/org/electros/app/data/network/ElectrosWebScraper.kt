package ua.org.electros.app.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ElectrosWebScraper(
    private val cookieStorage: CookieStorage? = null,
    private val authPreferences: AuthPreferences? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply { if (cookieStorage != null) cookieJar(cookieStorage) }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val wsFactory: WebSocket.Factory = client,
    private val wsAckWaitMs: Long = 1_000L,
    private val verifyDelayMs: Long = 3_000L,
    private val serialProvider: () -> String = { authPreferences?.deviceSerial.orEmpty() }
) {

    private val baseUrl = "https://electros.org.ua"
    private val wsUrl = "wss://electros.org.ua/ws/"
    private var lastCsrfToken: String? = null
    private var consecutiveAuthFailures = 0

    private fun currentSerial(): String = serialProvider().trim()

    suspend fun fetchTelemetry(): Result<TelemetryData> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val wsResult = queryWebSocket("site $serial")
            if (wsResult.isSuccess) {
                val data = wsResult.getOrThrow()
                if (data.isNotBlank() && data.contains("\n")) {
                    val telemetry = parseWebSocketTelemetry(data)
                    return@withContext Result.success(telemetry)
                }
            }
        }

        // Fallback to HTTP protected_page.php if WebSocket query fails
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

    suspend fun fetchTelemetryHttp(): Result<TelemetryData> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/auth/protected_page.php")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}"))
            }

            val finalUrl = response.request.url.toString()
            val html = response.body?.string().orEmpty()

            // Detect if redirected to login page
            if (finalUrl.contains("login.php") || finalUrl.contains("main.php") || (html.contains("name=\"password\"") && !html.contains("Станція"))) {
                if (consecutiveAuthFailures >= 2) {
                    return@withContext Result.failure(SecurityException("Authentication failed twice. Circuit breaker open."))
                }
                val loginResult = login()
                if (loginResult.isFailure) {
                    consecutiveAuthFailures++
                    return@withContext Result.failure(loginResult.exceptionOrNull() ?: IOException("Login failed"))
                }
                consecutiveAuthFailures = 0
                return@withContext fetchTelemetryHttp()
            }

            consecutiveAuthFailures = 0
            val doc = Jsoup.parse(html)
            extractCsrfToken(doc)

            val telemetry = parseTelemetryDocument(doc)
            Result.success(telemetry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(
        serial: String? = null,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetSerial = serial ?: authPreferences?.deviceSerial.orEmpty()
        val targetPassword = password ?: authPreferences?.internetPassword.orEmpty()

        if (targetSerial.isBlank() || targetPassword.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Введіть номер пристрою та пароль"))
        }

        try {
            val formBuilder = FormBody.Builder()
                .add("username", targetSerial)
                .add("password", targetPassword)

            lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

            val request = Request.Builder()
                .url("$baseUrl/auth/login.php")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                .post(formBuilder.build())
                .build()

            val response = client.newCall(request).execute()
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

    suspend fun stopCharge(): Result<Unit> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val wsResult = sendAndVerify("site $serial\nstop") { !it.isChargingActive }
            if (wsResult.isSuccess) return@withContext wsResult
        }
        executeAction("stop")
    }

    suspend fun setAmperage(amperes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val idx = amperageToIndex(amperes)
            val wsResult = sendAndVerify("site $serial\nstart\n$idx\n0\n") { it.selectedCurrentAmperes == amperes }
            if (wsResult.isSuccess) return@withContext wsResult
        }
        setAmperageHttp(amperes)
    }

    private fun setAmperageHttp(amperes: Int): Result<Unit> {
        val formBuilder = FormBody.Builder()
            .add("action", "set_current")
            .add("current", "$amperes A")
            .add("current", "$amperes")
            .add("amperage", "$amperes")
            .add("amper", "$amperes")
            .add("tok", "$amperes")

        lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

        val request = Request.Builder()
            .url("$baseUrl/auth/protected_page.php")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .post(formBuilder.build())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setTimer(startTime: String, stopTime: String): Result<Unit> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            val nowFormatted = SimpleDateFormat("dd.MM.yyyy'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val wsCmd = "site $serial\ndatetime\n$nowFormatted\n$startTime\n$stopTime\n"
            val wsResult = sendCommandWebSocket(wsCmd)
            if (wsResult.isSuccess) return@withContext wsResult
        }

        val formBuilder = FormBody.Builder()
            .add("action", "set_timer")
            .add("time_start", startTime)
            .add("time_stop", stopTime)

        lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

        val request = Request.Builder()
            .url("$baseUrl/auth/protected_page.php")
            .post(formBuilder.build())
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetKwh(): Result<Unit> = withContext(Dispatchers.IO) {
        val serial = currentSerial()
        if (serial.isNotBlank()) {
            sendCommandWebSocket("site $serial\nresetkwt")
        } else {
            Result.failure(IllegalStateException("Номер пристрою не налаштовано"))
        }
    }

    fun amperageToIndex(amperes: Int): Int = when (amperes) {
        32 -> 0
        30 -> 1
        28 -> 2
        26 -> 3
        24 -> 4
        22 -> 5
        20 -> 6
        18 -> 7
        16 -> 8
        14 -> 9
        12 -> 10
        10 -> 11
        8 -> 12
        6 -> 13
        else -> ((32 - amperes) / 2).coerceIn(0, 13)
    }

    private suspend fun executeAction(actionName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("action", actionName)

        lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

        val request = Request.Builder()
            .url("$baseUrl/auth/protected_page.php")
            .post(formBuilder.build())
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${response.code}"))
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

    fun parseWebSocketTelemetry(data: String): TelemetryData {
        val lines = data.split("\n")
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

        // Line 10: grid voltage
        val vCur = lines.getOrNull(10)?.trim()?.toIntOrNull() ?: if (isOnline) 220 else 0

        // Line 11: consumed energy in mWh (divide by 1,000,000 to get kWh)
        val energyKwh = (lines.getOrNull(11)?.trim()?.toDoubleOrNull() ?: 0.0) / 1_000_000.0

        // Line 12: power in dW (divide by 10,000 to get kW)
        val powerKw = (lines.getOrNull(12)?.trim()?.toDoubleOrNull() ?: 0.0) / 10_000.0

        // Line 13: neutral voltage
        val neutralV = lines.getOrNull(13)?.trim()?.toIntOrNull() ?: 2

        val vMax = lines.getOrNull(16)?.trim()?.toIntOrNull() ?: vCur
        val vMin = lines.getOrNull(17)?.trim()?.toIntOrNull() ?: vCur
        val vAvg = lines.getOrNull(20)?.trim()?.toIntOrNull() ?: vCur

        val nMax = lines.getOrNull(18)?.trim()?.toIntOrNull() ?: neutralV
        val nMin = lines.getOrNull(19)?.trim()?.toIntOrNull() ?: neutralV
        val nAvg = lines.getOrNull(21)?.trim()?.toIntOrNull() ?: neutralV

        val isChargingActive = statusCode == 2 || (isOnline && (measuredA > 0.5 || powerKw > 0.1))

        return TelemetryData(
            statusText = statusText,
            isOnline = isOnline,
            voltageMin = vMin,
            voltageAvg = vAvg,
            voltageCurrent = vCur,
            voltageMax = vMax,
            neutralMin = nMin,
            neutralAvg = nAvg,
            neutralCurrent = neutralV,
            neutralMax = nMax,
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
        var vMin = 0; var vAvg = 0; var vCur = 0; var vMax = 0
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
                    it.text().trim().replace(Regex("[^0-9]"), "").toIntOrNull()
                }
                if (numbers.size >= 4) {
                    vMin = numbers[0]
                    vAvg = numbers[1]
                    vCur = numbers[2]
                    vMax = numbers[3]
                } else if (numbers.size == 3) {
                    vMin = numbers[0]
                    vAvg = numbers[1]
                    vCur = numbers[1]
                    vMax = numbers[2]
                } else if (numbers.isNotEmpty()) {
                    vCur = numbers.last()
                    vAvg = numbers.last()
                }
            }
        }
        if (vCur == 0) {
            val voltMatcher = Regex("([1-9][0-9]{2})\\s*[ВVv]").find(bodyText)
            if (voltMatcher != null) {
                val foundV = voltMatcher.groupValues[1].toIntOrNull() ?: 0
                if (foundV in 100..450) {
                    vCur = foundV
                    vAvg = foundV
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
            val statusMatcher = Regex("Стан\\s*:\\s*([A-Za-zА-Яа-яІіЇїЄє0-9\\s.]+?)(?=Напруга|Min|Середня|Поточна|Оберіть|Струм|\\n|$)").find(bodyText)
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
            vCur > 50 -> true
            bodyText.contains("Напруга мережі", ignoreCase = true) || bodyText.contains("Напруга Мережі") -> true
            else -> false
        }

        val statusText = when {
            extractedStatus.isNotBlank() -> extractedStatus
            isOnline -> "Підключено"
            else -> "Станція OFFLINE"
        }

        // Neutral Voltage row
        var nMin = 0; var nAvg = 2; var nCur = 2; var nMax = 5
        val nTable = doc.select("table").firstOrNull { table ->
            val txt = table.text().lowercase()
            txt.contains("напруга") && txt.contains("нейтрал")
        }
        if (nTable != null) {
            val dataRows = nTable.select("tr").filter { it.select("td").isNotEmpty() }
            val lastDataRow = dataRows.lastOrNull()
            val cells = lastDataRow?.select("td")
            if (cells != null && cells.isNotEmpty()) {
                val numbers = cells.mapNotNull {
                    it.text().trim().replace(Regex("[^0-9]"), "").toIntOrNull()
                }
                if (numbers.size >= 4) {
                    nMin = numbers[0]
                    nAvg = numbers[1]
                    nCur = numbers[2]
                    nMax = numbers[3]
                }
            }
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
            val num = selectedOpt.text().replace(Regex("[^0-9]"), "").toIntOrNull()
                ?: selectedOpt.`val`().replace(Regex("[^0-9]"), "").toIntOrNull()
            if (num != null && num in 6..32) {
                selectedA = num
            }
        } else {
            val textMatch = Regex("Оберіть струм[^0-9]*([0-9]+)\\s*A?").find(bodyText)
            if (textMatch != null) {
                val num = textMatch.groupValues[1].toIntOrNull()
                if (num != null && num in 6..32) selectedA = num
            }
        }

        // Measured Current
        var measuredA = 0.0
        val currentMatcher = Regex("Струм,\\s*виміряний:\\s*([0-9.]+)\\s*A").find(bodyText)
        if (currentMatcher != null) {
            measuredA = currentMatcher.groupValues[1].toDoubleOrNull() ?: 0.0
        }

        // Consumed Energy
        var energyKwh = 0.0
        val energyRow = doc.select("tr:has(td:contains(Спожита енергія))").first()
        if (energyRow != null) {
            val m = Regex("([0-9.]+)\\s*кВт\\*год").find(energyRow.text())
            if (m != null) energyKwh = m.groupValues[1].toDoubleOrNull() ?: 0.0
        } else {
            val energyEl = doc.select(":containsOwn(кВт*год)").first()
            if (energyEl != null) {
                val raw = energyEl.parent()?.text().orEmpty()
                val m = Regex("([0-9.]+)\\s*кВт\\*год").find(raw)
                if (m != null) energyKwh = m.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }

        // Power kW (strictly distinguished from кВт*год)
        var powerKw = 0.0
        val powerRow = doc.select("tr:has(td:contains(Потужність))").first()
        if (powerRow != null) {
            val m = Regex("([0-9.]+)\\s*кВт(?!\\*год)").find(powerRow.text())
            if (m != null) powerKw = m.groupValues[1].toDoubleOrNull() ?: 0.0
        } else {
            val powerMatch = Regex("([0-9.]+)\\s*кВт(?!\\*год)").find(bodyText)
            if (powerMatch != null) {
                powerKw = powerMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }

        val isChargingActive = isOnline &&
                (measuredA > 0.5 || powerKw > 0.1 || (statusText.contains("Заряд", ignoreCase = true) && !statusText.contains("Немає заряду", ignoreCase = true)))

        return TelemetryData(
            statusText = statusText,
            isOnline = isOnline,
            voltageMin = vMin,
            voltageAvg = vAvg,
            voltageCurrent = vCur,
            voltageMax = vMax,
            neutralMin = nMin,
            neutralAvg = nAvg,
            neutralCurrent = nCur,
            neutralMax = nMax,
            selectedCurrentAmperes = selectedA,
            measuredCurrentAmperes = measuredA,
            consumedEnergyKwh = energyKwh,
            powerKw = powerKw,
            isChargingActive = isChargingActive
        )
    }
}
