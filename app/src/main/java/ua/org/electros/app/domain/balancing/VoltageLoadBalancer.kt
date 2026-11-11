package ua.org.electros.app.domain.balancing

class VoltageLoadBalancer(
    var isEnabled: Boolean = true,
    var lowThresholdV: Int = 185,
    var highThresholdV: Int = 205,
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

        // Low voltage trigger (< 185V): step down after 2 consecutive ticks
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

        // High voltage recovery (> 205V): step up after 5 consecutive ticks of stable voltage
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

