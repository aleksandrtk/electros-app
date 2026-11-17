package ua.org.electros.app.domain.balancing

import ua.org.electros.app.data.local.AppSettings

class VoltageLoadBalancer(
    var isEnabled: Boolean = true,
    var lowThresholdV: Int = AppSettings.DEFAULT_VOLTAGE_LOW_THRESHOLD,
    var highThresholdV: Int = AppSettings.DEFAULT_VOLTAGE_HIGH_THRESHOLD,
    var minAmperage: Int = 6,
    var maxAmperage: Int = 12,
    private val lowVoltageTriggerTicks: Int = 2,
    private val highVoltageRecoveryTicks: Int = 5
) {
    private var consecutiveLowTicks = 0
    private var consecutiveHighTicks = 0
    private var streakMinVoltage = Int.MAX_VALUE

    /**
     * Evaluates the current grid voltage and current station amperage.
     * Returns the recommended new amperage to apply, or null if no change is needed.
     */
    fun evaluate(currentVoltage: Int, currentAmperage: Int): Int? {
        if (!isEnabled || currentVoltage <= 0 || highThresholdV <= lowThresholdV) {
            reset()
            return null
        }

        // Emergency protection: drop immediately to minimum if voltage falls critically below 175V
        if (currentVoltage < 175 && currentAmperage > minAmperage) {
            reset()
            return minAmperage
        }

        // Low voltage trigger (below lowThresholdV, 190V by default): step down after 2 consecutive ticks
        if (currentVoltage < lowThresholdV) {
            consecutiveLowTicks++
            consecutiveHighTicks = 0
            streakMinVoltage = Int.MAX_VALUE
            if (consecutiveLowTicks >= lowVoltageTriggerTicks) {
                consecutiveLowTicks = 0
                if (currentAmperage > minAmperage) {
                    return (currentAmperage - 2).coerceAtLeast(minAmperage)
                }
            }
            return null
        }

        // Recovery in two stages, one 2 A step after 5 consecutive good ticks:
        // from partialThreshold (200 V by default) up to PARTIAL_RECOVERY_AMPERAGE (10 A),
        // from highThresholdV (205 V by default) up to maxAmperage.
        val partialThreshold = highThresholdV - PARTIAL_RECOVERY_MARGIN_V
        val recoveryFloor = if (partialThreshold > lowThresholdV) partialThreshold else highThresholdV
        if (currentVoltage >= recoveryFloor) {
            consecutiveHighTicks++
            consecutiveLowTicks = 0
            streakMinVoltage = minOf(streakMinVoltage, currentVoltage)
            if (consecutiveHighTicks >= highVoltageRecoveryTicks) {
                val ceiling = if (streakMinVoltage >= highThresholdV) {
                    maxAmperage
                } else {
                    minOf(PARTIAL_RECOVERY_AMPERAGE, maxAmperage)
                }
                consecutiveHighTicks = 0
                streakMinVoltage = Int.MAX_VALUE
                if (currentAmperage < ceiling) {
                    return (currentAmperage + 2).coerceAtMost(ceiling)
                }
            }
            return null
        }

        // Deadband [lowThresholdV, recoveryFloor): maintain current amperage, reset counters
        reset()
        return null
    }

    fun reset() {
        consecutiveLowTicks = 0
        consecutiveHighTicks = 0
        streakMinVoltage = Int.MAX_VALUE
    }

    companion object {
        /** The first recovery stage starts this many volts below [highThresholdV]. */
        const val PARTIAL_RECOVERY_MARGIN_V = 5
        /** Current the first recovery stage may raise to. */
        const val PARTIAL_RECOVERY_AMPERAGE = 10
    }
}

