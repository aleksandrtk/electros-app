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
)

