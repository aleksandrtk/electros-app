package ua.org.electros.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.domain.accumulator.EnergyAccumulator

class EnergyAccumulatorTest {

    @Test
    fun calculateTotalDeliveredKwh_singleSlice() {
        val finalizedSlices = emptyList<ChargeSliceEntity>()
        val total = EnergyAccumulator.calculateTotalDeliveredKwh(
            finalizedSlices = finalizedSlices,
            activeSliceStartKwh = 0.0,
            currentStationCounterKwh = 12.4
        )
        assertThat(total).isWithin(0.001).of(12.4)
    }

    @Test
    fun calculateTotalDeliveredKwh_acrossPowerBlackoutCounterReset() {
        // First slice before blackout delivered 12.4 kWh
        val slice1 = ChargeSliceEntity(
            id = 1,
            sessionId = "session-1",
            sliceIndex = 0,
            stationCounterStartKwh = 0.0,
            stationCounterLastKwh = 12.4,
            finalizedKwh = 12.4,
            startedAt = 1000L,
            endedAt = 2000L,
            terminationReason = SliceEndReason.POWER_OUTAGE
        )

        // Station rebooted to 0.0 after blackout, now reads 7.6 kWh
        val total = EnergyAccumulator.calculateTotalDeliveredKwh(
            finalizedSlices = listOf(slice1),
            activeSliceStartKwh = 0.0,
            currentStationCounterKwh = 7.6
        )

        // Total should be 12.4 + 7.6 = 20.0 kWh
        assertThat(total).isWithin(0.001).of(20.0)
    }

    @Test
    fun isCounterRegression_detectsSuddenCounterDrop() {
        val hasRegression = EnergyAccumulator.isCounterRegression(
            currentReadingKwh = 0.0,
            lastSampledReadingKwh = 14.2
        )
        assertThat(hasRegression).isTrue()

        val normalIncrease = EnergyAccumulator.isCounterRegression(
            currentReadingKwh = 14.5,
            lastSampledReadingKwh = 14.2
        )
        assertThat(normalIncrease).isFalse()
    }

    @Test
    fun calculateDeadManTimerMinutes_computesExpectedDurationWithBuffer() {
        // Remaining: 7.4 kWh, 230V, 16A -> Power = ~3.68 kW -> 2 hours + 15 min buffer = 135 minutes
        val timerMinutes = EnergyAccumulator.calculateDeadManTimerMinutes(
            remainingKwh = 7.36,
            voltageVolts = 230,
            amperes = 16,
            bufferMinutes = 15
        )
        assertThat(timerMinutes).isEqualTo(135)
    }
}

