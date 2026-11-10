package ua.org.electros.app.domain.accumulator

import ua.org.electros.app.data.local.entity.ChargeSliceEntity

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
        val requiredMinutes = (requiredHours * 60).toInt()
        return requiredMinutes + bufferMinutes
    }
}

