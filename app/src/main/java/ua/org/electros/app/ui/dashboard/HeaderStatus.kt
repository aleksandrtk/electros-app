package ua.org.electros.app.ui.dashboard

import ua.org.electros.app.R
import ua.org.electros.app.ui.UiText
import java.util.Locale

/**
 * Maps the station status text to the header label. Fault and prohibition states are matched
 * before the generic "заряджання" check, because "Заборона заряджання" and "Помилка заряджання"
 * also contain that word and were previously reported as CHARGING.
 */
internal fun formatHeaderStatus(statusText: String, isOnline: Boolean): UiText {
    val raw = statusText.trim()
    val id = when {
        !isOnline -> R.string.status_offline
        raw.contains("Очікування зупинки", ignoreCase = true) -> R.string.status_stopping
        raw.contains("Заборона заряджання", ignoreCase = true) -> R.string.status_forbidden
        raw.contains("Помилка заряджання", ignoreCase = true) -> R.string.status_charge_error
        raw.contains("Помилка зв'язку", ignoreCase = true) -> R.string.status_comm_error
        raw.contains("Напруга нижче", ignoreCase = true) -> R.string.status_low_voltage
        raw.contains("Ток витоку", ignoreCase = true) -> R.string.status_leakage
        raw.contains("перевищив 40", ignoreCase = true) -> R.string.status_overcurrent
        raw.contains("Чекаю підключення", ignoreCase = true) || raw.contains("Waiting", ignoreCase = true) -> R.string.status_waiting
        raw.contains("Підключено. Немає заряду", ignoreCase = true) -> R.string.status_idle
        raw.contains("Заряджання", ignoreCase = true) || raw.contains("Заряджається", ignoreCase = true) || raw.contains("Charging", ignoreCase = true) -> R.string.status_charging
        raw.contains("Станція OFFLINE", ignoreCase = true) || raw.contains("OFFLINE", ignoreCase = true) -> R.string.status_offline
        raw.contains("В МЕРЕЖІ", ignoreCase = true) || raw.contains("Підключено", ignoreCase = true) || raw.contains("ONLINE", ignoreCase = true) -> R.string.status_online
        raw.isNotBlank() -> return UiText.Raw(raw.uppercase(Locale.ROOT))
        else -> R.string.status_online
    }
    return UiText.Res(id)
}
