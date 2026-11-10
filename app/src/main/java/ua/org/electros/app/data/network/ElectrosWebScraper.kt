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
