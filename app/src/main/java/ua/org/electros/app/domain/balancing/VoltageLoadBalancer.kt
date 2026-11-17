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

    /**
     * Evaluates the current grid voltage and current station amperage.
     * Returns the recommended new amperage to apply, or null if no change is needed.
     */
    fun evaluate(currentVoltage: Int, currentAmperage: Int): Int? {
        if (!isEnabled || currentVoltage <= 0 || highThresholdV <= lowThresholdV) {
            consecutiveLowTicks = 0
            consecutiveHighTicks = 0
            return null
        }

        // Emergency protection: drop immediately to minimum if voltage falls critically below 175V
        if (currentVoltage < 175 && currentAmperage > minAmperage) {
            consecutiveLowTicks = 0
            consecutiveHighTicks = 0
            return minAmperage
        }

        // Low voltage trigger (below lowThresholdV, 190V by default): step down after 2 consecutive ticks
        if (currentVoltage < lowThresholdV) {
            consecutiveLowTicks++
            consecutiveHighTicks = 0
            if (consecutiveLowTicks >= lowVoltageTriggerTicks) {
                consecutiveLowTicks = 0
                if (currentAmperage > minAmperage) {
                    return (currentAmperage - 2).coerceAtLeast(minAmperage)
                }
            }
            return null
        }

        // High voltage recovery (above highThresholdV, 205V by default): step up after 5 consecutive ticks of stable voltage
        if (currentVoltage > highThresholdV) {
            consecutiveHighTicks++
            consecutiveLowTicks = 0
            if (consecutiveHighTicks >= highVoltageRecoveryTicks) {
                consecutiveHighTicks = 0
                if (currentAmperage < maxAmperage) {
                    return (currentAmperage + 2).coerceAtMost(maxAmperage)
                }
            }
            return null
        }

        // Deadband [lowThresholdV..highThresholdV]: maintain current amperage, reset counters
        consecutiveLowTicks = 0
        consecutiveHighTicks = 0
        return null
    }

    fun reset() {
        consecutiveLowTicks = 0
        consecutiveHighTicks = 0
    }
}

