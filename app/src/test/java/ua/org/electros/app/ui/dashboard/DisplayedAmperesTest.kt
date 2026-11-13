package ua.org.electros.app.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.model.TelemetryData

class DisplayedAmperesTest {

    private fun telemetry(
        selectedA: Int,
        charging: Boolean = true,
        online: Boolean = true
    ) = TelemetryData(
        statusText = "",
        isOnline = online,
        voltageCurrent = 230,
        selectedCurrentAmperes = selectedA,
        measuredCurrentAmperes = if (charging) selectedA.toDouble() else 0.0,
        consumedEnergyKwh = 0.0,
        powerKw = 0.0,
        isChargingActive = charging
    )

    @Test
    fun charging_showsLiveStationCurrent_whenDlbRampedAboveKnob() {
        assertThat(resolveDisplayedAmperes(telemetry(selectedA = 12), selectedAmperes = 6)).isEqualTo(12)
    }

    @Test
    fun charging_showsLiveStationCurrent_whenDlbDeratedBelowKnob() {
        assertThat(resolveDisplayedAmperes(telemetry(selectedA = 8), selectedAmperes = 10)).isEqualTo(8)
    }

    @Test
    fun notCharging_showsKnobValue_evenIfStationHoldsOldCurrent() {
        assertThat(resolveDisplayedAmperes(telemetry(selectedA = 16, charging = false), selectedAmperes = 6)).isEqualTo(6)
    }

    @Test
    fun offlineOrNoTelemetry_showsKnobValue() {
        assertThat(resolveDisplayedAmperes(telemetry(selectedA = 16, online = false), selectedAmperes = 10)).isEqualTo(10)
        assertThat(resolveDisplayedAmperes(null, selectedAmperes = 10)).isEqualTo(10)
    }
}
