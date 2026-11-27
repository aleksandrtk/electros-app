package ua.org.electros.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.org.electros.app.R
import ua.org.electros.app.domain.balancing.VoltageLoadBalancer

/** What auto-current is doing right now, as shown next to its switch on the main screen. */
internal sealed interface AutoCurrentStatus {
    data object Off : AutoCurrentStatus
    data object NoData : AutoCurrentStatus
    data class Low(val voltage: Int) : AutoCurrentStatus
    /** The current was lowered and is held until the voltage reaches [voltage]. */
    data class Waiting(val voltage: Int) : AutoCurrentStatus
    data object Ok : AutoCurrentStatus
}

/**
 * Mirrors the steps of [VoltageLoadBalancer]: between the low threshold and the first recovery
 * stage the current is held, so "voltage OK" there would wrongly suggest nothing is limited.
 */
internal fun resolveAutoCurrentStatus(
    enabled: Boolean,
    isOnline: Boolean,
    voltage: Int?,
    isCharging: Boolean,
    stationAmperes: Int?,
    lowThreshold: Int,
    highThreshold: Int,
    maxAmperage: Int,
    stationLowVoltageStop: Boolean = false
): AutoCurrentStatus {
    if (!enabled) return AutoCurrentStatus.Off
    if (!isOnline || voltage == null || voltage <= 0) return AutoCurrentStatus.NoData
    // The station's own protection cut the charge on a sag under load; the idle voltage read
    // afterwards can look fine, so "voltage OK" next to "low voltage" in the header would be wrong.
    if (voltage < lowThreshold || stationLowVoltageStop) return AutoCurrentStatus.Low(voltage)
    if (isCharging && stationAmperes != null && stationAmperes < maxAmperage && highThreshold > lowThreshold) {
        val stages = VoltageLoadBalancer.recoveryStages(lowThreshold, highThreshold, maxAmperage)
        val ceiling = VoltageLoadBalancer.ceilingFor(stages, voltage) ?: 0
        if (stationAmperes >= ceiling) {
            // Held at this stage's ceiling: name the voltage of the next stage that allows more.
            val next = stages.firstOrNull { it.floorV > voltage && it.ceilingA > stationAmperes }
            if (next != null) return AutoCurrentStatus.Waiting(next.floorV)
        }
    }
    return AutoCurrentStatus.Ok
}

@Composable
internal fun autoCurrentLabel(status: AutoCurrentStatus): String = when (status) {
    AutoCurrentStatus.Off -> stringResource(R.string.dlb_off_short)
    AutoCurrentStatus.NoData -> stringResource(R.string.dlb_standby)
    is AutoCurrentStatus.Low -> stringResource(R.string.dlb_derating, status.voltage)
    is AutoCurrentStatus.Waiting -> stringResource(R.string.dlb_waiting, status.voltage)
    AutoCurrentStatus.Ok -> stringResource(R.string.dlb_normal)
}
