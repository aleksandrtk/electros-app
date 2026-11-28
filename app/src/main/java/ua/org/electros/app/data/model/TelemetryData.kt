package ua.org.electros.app.data.model

data class TelemetryData(
    val statusText: String,
    val isOnline: Boolean,
    val voltageCurrent: Int?,
    val selectedCurrentAmperes: Int,
    val measuredCurrentAmperes: Double,
    val consumedEnergyKwh: Double,
    val powerKw: Double,
    val isChargingActive: Boolean,
    /** False for the HTTP fallback, whose energy counter is rounded to 0.1 kWh (WebSocket reports mWh). */
    val isPreciseCounter: Boolean = true
) {
    /**
     * The station paused charging with its own undervoltage protection (WS status 5). It resumes by
     * itself once the voltage recovers, so this is a pause, not the end of the charge.
     */
    val isLowVoltageStop: Boolean
        get() = statusText.contains(LOW_VOLTAGE_STATUS_MARKER, ignoreCase = true)

    /** Charging is blocked on the station (WS status 4); "Почати" lifts it. */
    val isChargingForbidden: Boolean
        get() = statusText.contains(FORBIDDEN_STATUS_MARKER, ignoreCase = true)

    companion object {
        const val LOW_VOLTAGE_STATUS_MARKER = "Напруга нижче"
        const val FORBIDDEN_STATUS_MARKER = "Заборона заряджання"
    }
}

