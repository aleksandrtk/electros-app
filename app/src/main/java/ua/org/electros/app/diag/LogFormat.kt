package ua.org.electros.app.diag

import ua.org.electros.app.data.model.TelemetryData
import java.util.Locale

/** Compact, secret-free description of a telemetry reading for the journal. */
fun TelemetryData.toLogString(): String = String.format(
    Locale.US,
    "%s%s '%s' U=%sV set=%dA I=%.1fA P=%.2fkW counter=%.3fkWh%s",
    if (isOnline) "online" else "OFFLINE",
    if (isChargingActive) " charging" else " idle",
    statusText,
    voltageCurrent?.toString() ?: "?",
    selectedCurrentAmperes,
    measuredCurrentAmperes,
    powerKw,
    consumedEnergyKwh,
    if (isPreciseCounter) "" else " (http, rounded)"
)

fun Double.kwh(): String = String.format(Locale.US, "%.3f", this)

fun Throwable?.reason(): String = this?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown error"
