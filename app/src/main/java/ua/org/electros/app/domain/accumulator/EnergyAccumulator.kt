package ua.org.electros.app.domain.accumulator

import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object EnergyAccumulator {

    /**
     * Calculates total energy delivered across all finalized slices plus the active live slice.
     */
    fun calculateTotalDeliveredKwh(
        finalizedSlices: List<ChargeSliceEntity>,
        activeSliceStartKwh: Double,
        currentStationCounterKwh: Double
    ): Double {
        val completedSlicesSum = finalizedSlices.sumOf { it.finalizedKwh }
        val activeSliceDelta = (currentStationCounterKwh - activeSliceStartKwh).coerceAtLeast(0.0)
        return completedSlicesSum + activeSliceDelta
    }

    /**
     * Returns true if a counter regression occurred (station rebooted after a rapid power cut).
     */
    fun isCounterRegression(
        currentReadingKwh: Double,
        lastSampledReadingKwh: Double
    ): Boolean {
        return lastSampledReadingKwh > 0.1 && currentReadingKwh < (lastSampledReadingKwh - 0.05)
    }

    /**
     * Calculates remaining quota toward the target.
     */
    fun calculateRemainingKwh(
        targetKwh: Double,
        totalDeliveredKwh: Double
    ): Double {
        return (targetKwh - totalDeliveredKwh).coerceAtLeast(0.0)
    }

    /**
     * Computes the dead-man hardware timer duration in minutes based on remaining quota,
     * voltage, and amperage, adding a safety buffer of 15 minutes.
     */
    fun calculateDeadManTimerMinutes(
        remainingKwh: Double,
        voltageVolts: Int,
        amperes: Int,
        bufferMinutes: Int = 15
    ): Int {
        val effectiveVoltage = voltageVolts.coerceAtLeast(200)
        val powerKw = (effectiveVoltage * amperes) / 1000.0
        if (powerKw <= 0.1) return 60 // fallback 1 hour

        val requiredHours = remainingKwh / powerKw
        val requiredMinutes = (requiredHours * 60.0).roundToInt()
        return requiredMinutes + bufferMinutes
    }

    /**
     * Computes hardware timer window (HH:mm start to HH:mm stop) as a fail-safe cutoff
     * for sessions with a kWh or duration target. Returns null if unlimited or >= 23h
     * (to avoid 24-hour clock wraparound stopping the station prematurely).
     */
    fun computeDeadManScheduleWindow(
        now: LocalTime,
        targetKwh: Double,
        deliveredKwh: Double,
        targetDurationMinutes: Int,
        accumulatedChargingMs: Long,
        voltageVolts: Int,
        amperes: Int,
        isScheduleEnabled: Boolean = false,
        scheduleStartTime: String = "23:00",
        scheduleStopTime: String = "07:00"
    ): Pair<String, String>? {
        val minutesByKwh = if (targetKwh > 0.0) {
            calculateDeadManTimerMinutes(
                remainingKwh = calculateRemainingKwh(targetKwh, deliveredKwh),
                voltageVolts = voltageVolts,
                amperes = amperes
            )
        } else Int.MAX_VALUE

        val minutesByDuration = if (targetDurationMinutes > 0) {
            val remainingDurMin = ((targetDurationMinutes * 60_000L - accumulatedChargingMs)
                .coerceAtLeast(0L) / 60_000L).toInt()
            remainingDurMin + 15
        } else Int.MAX_VALUE

        val rawMinutes = minOf(minutesByKwh, minutesByDuration)
        if (rawMinutes == Int.MAX_VALUE || rawMinutes >= 23 * 60) return null

        val safeMinutes = rawMinutes.coerceIn(15, 23 * 60 - 1)
        val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        val deadManStop = now.plusMinutes(safeMinutes.toLong())

        if (isScheduleEnabled) {
            val schedStart = runCatching { LocalTime.parse(scheduleStartTime, fmt) }.getOrNull()
            val schedStop = runCatching { LocalTime.parse(scheduleStopTime, fmt) }.getOrNull()
            if (schedStart != null && schedStop != null && schedStart != schedStop) {
                val insideWindow = if (schedStart.isBefore(schedStop)) {
                    !now.isBefore(schedStart) && now.isBefore(schedStop)
                } else {
                    !now.isBefore(schedStart) || now.isBefore(schedStop)
                }
                if (insideWindow) {
                    val minsUntilSchedStop = ((schedStop.toSecondOfDay() - now.toSecondOfDay() + 86_400) % 86_400) / 60
                    val chosenStop = if (minsUntilSchedStop in 1 until safeMinutes) schedStop else deadManStop
                    return scheduleStartTime to chosenStop.format(fmt)
                }
            }
        }

        return now.format(fmt) to deadManStop.format(fmt)
    }
}

