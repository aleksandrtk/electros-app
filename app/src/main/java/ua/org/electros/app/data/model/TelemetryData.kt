package ua.org.electros.app.data.model

data class TelemetryData(
    val statusText: String, // e.g. "Станція ONLINE" or "Станція OFFLINE"
    val isOnline: Boolean,
    val voltageMin: Int,
    val voltageAvg: Int,
    val voltageCurrent: Int,
    val voltageMax: Int,
    val neutralMin: Int,
    val neutralAvg: Int,
    val neutralCurrent: Int,
    val neutralMax: Int,
    val selectedCurrentAmperes: Int,
    val measuredCurrentAmperes: Double,
    val consumedEnergyKwh: Double,
    val powerKw: Double,
    val isChargingActive: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

