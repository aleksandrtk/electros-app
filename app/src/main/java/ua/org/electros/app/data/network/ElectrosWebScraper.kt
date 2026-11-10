package ua.org.electros.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.model.TelemetryData
import java.io.IOException
import java.util.concurrent.TimeUnit

class ElectrosWebScraper(
    private val cookieStorage: CookieStorage? = null,
    private val authPreferences: AuthPreferences? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply { if (cookieStorage != null) cookieJar(cookieStorage) }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    private val baseUrl = "https://electros.org.ua"
    private var lastCsrfToken: String? = null
    private var consecutiveAuthFailures = 0

    suspend fun fetchTelemetry(): Result<TelemetryData> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/auth/main.php")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}"))
            }

            val finalUrl = response.request.url.toString()
            val html = response.body?.string().orEmpty()

            // Detect if redirected to login page
            if (finalUrl.contains("login.php") || html.contains("name=\"password\"") && !html.contains("Станція")) {
                if (consecutiveAuthFailures >= 2) {
                    return@withContext Result.failure(SecurityException("Authentication failed twice. Circuit breaker open."))
                }
                val loginResult = login()
                if (loginResult.isFailure) {
                    consecutiveAuthFailures++
                    return@withContext Result.failure(loginResult.exceptionOrNull() ?: IOException("Login failed"))
                }
                consecutiveAuthFailures = 0
                return@withContext fetchTelemetry()
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

    suspend fun startCharge(): Result<Unit> = executeAction("start")

    suspend fun stopCharge(): Result<Unit> = executeAction("stop")

    suspend fun setAmperage(amperes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("action", "set_current")
            .add("current", "$amperes A")
            .add("current", "$amperes")
            .add("amperage", "$amperes")
            .add("amper", "$amperes")
            .add("tok", "$amperes")

        lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

        val request = Request.Builder()
            .url("$baseUrl/auth/main.php")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .post(formBuilder.build())
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setTimer(startTime: String, stopTime: String): Result<Unit> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("action", "set_timer")
            .add("time_start", startTime)
            .add("time_stop", stopTime)

        lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

        val request = Request.Builder()
            .url("$baseUrl/auth/main.php")
            .post(formBuilder.build())
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeAction(actionName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("action", actionName)

        lastCsrfToken?.let { formBuilder.add("csrf_token", it) }

        val request = Request.Builder()
            .url("$baseUrl/auth/main.php")
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

    fun parseTelemetryDocument(doc: Document): TelemetryData {
        val bodyText = doc.body().text()

        // Station online/offline parsing
        val isOffline = bodyText.contains("Станція OFFLINE", ignoreCase = true) ||
                (bodyText.contains("OFFLINE", ignoreCase = true) && !bodyText.contains("ONLINE", ignoreCase = true))

        var extractedStatus = ""
        val statusEl = doc.select(":containsOwn(Стан:)").first()
        if (statusEl != null) {
            val raw = statusEl.text().substringAfter("Стан:").trim()
            if (raw.isNotBlank()) {
                extractedStatus = raw.substringBefore("Напруга").substringBefore("Min").trim()
            }
        }
        if (extractedStatus.isBlank()) {
            val statusMatcher = Regex("Стан:\\s*([A-Za-zА-Яа-яІіЇїЄє0-9\\s.]+?)(?=Напруга|Min|Оберіть|\\n|$)").find(bodyText)
            if (statusMatcher != null) {
                extractedStatus = statusMatcher.groupValues[1].trim()
            }
        }

        val isOnline = !isOffline && (extractedStatus.isNotBlank() || bodyText.contains("Напруга Мережі"))
        val statusText = if (extractedStatus.isNotBlank()) {
            extractedStatus
        } else if (isOnline) {
            "Підключено"
        } else {
            "Станція OFFLINE"
        }

        // Grid Voltage row: Min / Середня / Поточна / Max
        var vMin = 220; var vAvg = 220; var vCur = 220; var vMax = 220
        val vTable = doc.select("table:contains(Напруга Мережі)")
        if (vTable.isNotEmpty()) {
            val cells = vTable.select("tr").last()?.select("td")
            if (cells != null && cells.size >= 4) {
                vMin = cells[0].text().trim().toIntOrNull() ?: 220
                vAvg = cells[1].text().trim().toIntOrNull() ?: 220
                vCur = cells[2].text().trim().toIntOrNull() ?: 220
                vMax = cells[3].text().trim().toIntOrNull() ?: 220
            }
        }

        // Neutral Voltage row
        var nMin = 0; var nAvg = 2; var nCur = 2; var nMax = 5
        val nTable = doc.select("table:contains(Напруга нейтралі)")
        if (nTable.isNotEmpty()) {
            val cells = nTable.select("tr").last()?.select("td")
            if (cells != null && cells.size >= 4) {
                nMin = cells[0].text().trim().toIntOrNull() ?: 0
                nAvg = cells[1].text().trim().toIntOrNull() ?: 2
                nCur = cells[2].text().trim().toIntOrNull() ?: 2
                nMax = cells[3].text().trim().toIntOrNull() ?: 5
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

        val isChargingActive = !isOffline &&
                (measuredA > 0.5 || powerKw > 0.1 || statusText.contains("Заряд", ignoreCase = true)) &&
                !statusText.contains("Немає заряду", ignoreCase = true)

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
