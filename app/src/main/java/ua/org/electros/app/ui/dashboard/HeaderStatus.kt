package ua.org.electros.app.ui.dashboard

import java.util.Locale

/**
 * Maps the station status text to the header label. Fault and prohibition states are matched
 * before the generic "заряджання" check, because "Заборона заряджання" and "Помилка заряджання"
 * also contain that word and were previously reported as CHARGING.
 */
internal fun formatHeaderStatus(statusText: String, isOnline: Boolean): String {
    val raw = statusText.trim()
    return when {
        !isOnline -> "OFFLINE"
        raw.contains("Заборона заряджання", ignoreCase = true) -> "CHARGING FORBIDDEN"
        raw.contains("Помилка заряджання", ignoreCase = true) -> "CHARGE ERROR"
        raw.contains("Помилка зв'язку", ignoreCase = true) -> "COMMUNICATION ERROR"
        raw.contains("Напруга нижче", ignoreCase = true) -> "LOW VOLTAGE"
        raw.contains("Ток витоку", ignoreCase = true) -> "LEAKAGE DETECTED"
        raw.contains("перевищив 40", ignoreCase = true) -> "OVERCURRENT > 40A"
        raw.contains("Чекаю підключення", ignoreCase = true) || raw.contains("Waiting", ignoreCase = true) -> "WAITING FOR VEHICLE"
        raw.contains("Підключено. Немає заряду", ignoreCase = true) -> "CONNECTED • IDLE"
        raw.contains("Заряджання", ignoreCase = true) || raw.contains("Заряджається", ignoreCase = true) || raw.contains("Charging", ignoreCase = true) -> "CHARGING"
        raw.contains("Станція OFFLINE", ignoreCase = true) || raw.contains("OFFLINE", ignoreCase = true) -> "OFFLINE"
        raw.contains("В МЕРЕЖІ", ignoreCase = true) || raw.contains("Підключено", ignoreCase = true) || raw.contains("ONLINE", ignoreCase = true) -> "ONLINE"
        raw.isNotBlank() -> raw.uppercase(Locale.US)
        else -> "ONLINE"
    }
}
