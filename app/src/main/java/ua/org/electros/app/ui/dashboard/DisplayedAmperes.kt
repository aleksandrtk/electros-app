package ua.org.electros.app.ui.dashboard

import ua.org.electros.app.data.model.TelemetryData

/**
 * Current shown in the header pill. While the station is actually charging it reports the live
 * current (manual change, DLB derate or DLB ramp-up); otherwise it mirrors the value on the knob.
 */
internal fun resolveDisplayedAmperes(telemetry: TelemetryData?, selectedAmperes: Int): Int =
    if (telemetry != null &&
        telemetry.isOnline &&
        telemetry.isChargingActive &&
        telemetry.selectedCurrentAmperes in 6..32
    ) {
        telemetry.selectedCurrentAmperes
    } else {
        selectedAmperes
    }
