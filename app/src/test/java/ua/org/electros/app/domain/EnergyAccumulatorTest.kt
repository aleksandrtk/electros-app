package ua.org.electros.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import java.time.LocalTime

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

    @Test
    fun computeDeadManScheduleWindow_kwhTargetNoSchedule_setsNowToDeadline() {
        val window = EnergyAccumulator.computeDeadManScheduleWindow(
            now = LocalTime.of(22, 30),
            targetKwh = 10.0,
            deliveredKwh = 2.64,
            targetDurationMinutes = 0,
            accumulatedChargingMs = 0L,
            voltageVolts = 230,
            amperes = 16
        )
        assertThat(window).isEqualTo("22:30" to "00:45")
    }

    @Test
    fun computeDeadManScheduleWindow_durationExceeds23Hours_returnsNullToAvoidWraparound() {
        val window = EnergyAccumulator.computeDeadManScheduleWindow(
            now = LocalTime.of(10, 0),
            targetKwh = 80.0,
            deliveredKwh = 0.0,
            targetDurationMinutes = 0,
            accumulatedChargingMs = 0L,
            voltageVolts = 200,
            amperes = 6 // 1.2 kW -> >66 hours
        )
        assertThat(window).isNull()
    }

    @Test
    fun computeDeadManScheduleWindow_scheduleEnabled_takesEarlierStopWhileKeepingScheduleStart() {
        // Inside 23:00..07:00 at 05:30; dead-man wants 135m (07:45), schedule stops sooner at 07:00.
        val earlierSched = EnergyAccumulator.computeDeadManScheduleWindow(
            now = LocalTime.of(5, 30),
            targetKwh = 7.36,
            deliveredKwh = 0.0,
            targetDurationMinutes = 0,
            accumulatedChargingMs = 0L,
            voltageVolts = 230,
            amperes = 16,
            isScheduleEnabled = true,
            scheduleStartTime = "23:00",
            scheduleStopTime = "07:00"
        )
        assertThat(earlierSched).isEqualTo("23:00" to "07:00")

        // Inside 23:00..07:00 at 23:30; dead-man wants 75m (00:45) which is before 07:00.
        val earlierDeadMan = EnergyAccumulator.computeDeadManScheduleWindow(
            now = LocalTime.of(23, 30),
            targetKwh = 0.0,
            deliveredKwh = 0.0,
            targetDurationMinutes = 120,
            accumulatedChargingMs = 60 * 60_000L, // 60 min remaining + 15 buffer = 75 min
            voltageVolts = 230,
            amperes = 16,
            isScheduleEnabled = true,
            scheduleStartTime = "23:00",
            scheduleStopTime = "07:00"
        )
        assertThat(earlierDeadMan).isEqualTo("23:00" to "00:45")
    }
}

