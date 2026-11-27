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

        // Recovery in three stages, one 2 A step after 5 consecutive good ticks (203 V restore threshold
        // by default): from 193 V up to 8 A, from 198 V up to 10 A, from 203 V up to maxAmperage.
        // The ceiling follows the lowest voltage seen during the streak.
        val stages = recoveryStages(lowThresholdV, highThresholdV, maxAmperage)
        val recoveryFloor = stages.first().floorV
        if (currentVoltage >= recoveryFloor) {
            consecutiveHighTicks++
            consecutiveLowTicks = 0
            streakMinVoltage = minOf(streakMinVoltage, currentVoltage)
            if (consecutiveHighTicks >= highVoltageRecoveryTicks) {
                val ceiling = ceilingFor(stages, streakMinVoltage) ?: minAmperage
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

    /** From [floorV] volts the current may be raised up to [ceilingA]. */
    data class RecoveryStage(val floorV: Int, val ceilingA: Int)

    companion object {
        /** The 8 A stage starts this many volts below the restore threshold. */
        const val FIRST_STAGE_MARGIN_V = 10
        const val FIRST_STAGE_AMPERAGE = 8
        /** The 10 A stage starts this many volts below the restore threshold. */
        const val SECOND_STAGE_MARGIN_V = 5
        const val SECOND_STAGE_AMPERAGE = 10

        /**
         * Recovery stages, lowest first. A stage whose floor would fall below [lowV] is dropped (the
         * thresholds keep at least 10 V apart, so normally all three apply).
         */
        fun recoveryStages(lowV: Int, highV: Int, maxAmperage: Int): List<RecoveryStage> =
            listOf(
                RecoveryStage(highV - FIRST_STAGE_MARGIN_V, minOf(FIRST_STAGE_AMPERAGE, maxAmperage)),
                RecoveryStage(highV - SECOND_STAGE_MARGIN_V, minOf(SECOND_STAGE_AMPERAGE, maxAmperage)),
                RecoveryStage(highV, maxAmperage)
            ).filterIndexed { i, stage -> i == 2 || stage.floorV >= lowV }

        /** Highest current allowed at [voltage], or null below the first stage. */
        fun ceilingFor(stages: List<RecoveryStage>, voltage: Int): Int? =
            stages.lastOrNull { voltage >= it.floorV }?.ceilingA
    }
}

