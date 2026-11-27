package ua.org.electros.app.domain.balancing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoltageLoadBalancerTest {

    @Test
    fun evaluate_whenDisabled_returnsNull() {
        val balancer = VoltageLoadBalancer(isEnabled = false, lowThresholdV = 185, highThresholdV = 205)
        assertThat(balancer.evaluate(180, 10)).isNull()
        assertThat(balancer.evaluate(180, 10)).isNull()
    }

    @Test
    fun evaluate_voltageLow_stepsDownAfterTwoTicks() {
        val balancer = VoltageLoadBalancer(lowThresholdV = 185, highThresholdV = 205, minAmperage = 6)
        
        // First tick below threshold: counter increments, returns null
        val tick1 = balancer.evaluate(182, 10)
        assertThat(tick1).isNull()

        // Second tick below threshold: triggers step down
        val tick2 = balancer.evaluate(182, 10)
        assertThat(tick2).isEqualTo(8)

        // Continues to step down if still low
        balancer.evaluate(180, 8)
        val tick4 = balancer.evaluate(180, 8)
        assertThat(tick4).isEqualTo(6)

        // At minAmperage (6A): does not go below minAmperage
        balancer.evaluate(180, 6)
        val tick6 = balancer.evaluate(180, 6)
        assertThat(tick6).isNull()
    }

    @Test
    fun defaults_startLoweringBelow190V() {
        assertThat(ua.org.electros.app.data.local.AppSettings().voltageLowThreshold).isEqualTo(190)

        val balancer = VoltageLoadBalancer()
        // 190 V itself is still fine.
        assertThat(balancer.evaluate(190, 12)).isNull()
        assertThat(balancer.evaluate(190, 12)).isNull()
        // 189 V for two polls lowers the current by one step.
        assertThat(balancer.evaluate(189, 12)).isNull()
        assertThat(balancer.evaluate(189, 12)).isEqualTo(10)
    }

    @Test
    fun evaluate_criticalVoltageDrop_immediatelyDropsToMinAmperage() {
        val balancer = VoltageLoadBalancer(lowThresholdV = 185, minAmperage = 6)
        // Voltage critically low (< 175V)
        val result = balancer.evaluate(172, 12)
        assertThat(result).isEqualTo(6)
    }

    @Test
    fun evaluate_deadband_maintainsCurrentAmperage() {
        val balancer = VoltageLoadBalancer(lowThresholdV = 185, highThresholdV = 205)
        for (v in 185..199) {
            assertThat(balancer.evaluate(v, 8)).isNull()
        }
    }

    @Test
    fun defaults_recoverIn3Stages_193To8A_198To10A_203ToMax() {
        assertThat(ua.org.electros.app.data.local.AppSettings().voltageHighThreshold).isEqualTo(203)
        val balancer = VoltageLoadBalancer(maxAmperage = 16)

        // 190..192 V: held.
        repeat(10) { assertThat(balancer.evaluate(192, 6)).isNull() }
        // 193..197 V: up to 8 A only.
        repeat(4) { assertThat(balancer.evaluate(194, 6)).isNull() }
        assertThat(balancer.evaluate(193, 6)).isEqualTo(8)
        repeat(10) { assertThat(balancer.evaluate(197, 8)).isNull() }
        // 198..202 V: up to 10 A.
        repeat(4) { assertThat(balancer.evaluate(199, 8)).isNull() }
        assertThat(balancer.evaluate(198, 8)).isEqualTo(10)
        repeat(10) { assertThat(balancer.evaluate(202, 10)).isNull() }
        // 203 V and above: on to the maximum.
        repeat(4) { assertThat(balancer.evaluate(203, 10)).isNull() }
        assertThat(balancer.evaluate(205, 10)).isEqualTo(12)
        repeat(4) { assertThat(balancer.evaluate(206, 12)).isNull() }
        assertThat(balancer.evaluate(206, 12)).isEqualTo(14)
    }

    @Test
    fun recovery_streakDippingBelowAStage_usesTheLowestVoltageSeen() {
        val balancer = VoltageLoadBalancer(lowThresholdV = 190, highThresholdV = 203, maxAmperage = 12)
        listOf(205, 205, 196, 205).forEach { assertThat(balancer.evaluate(it, 8)).isNull() }
        // The streak saw 196 V (8 A stage), so 8 A stays.
        assertThat(balancer.evaluate(205, 8)).isNull()
        // A clean streak raises it.
        repeat(4) { assertThat(balancer.evaluate(205, 8)).isNull() }
        assertThat(balancer.evaluate(205, 8)).isEqualTo(10)
    }

    @Test
    fun stages_followTheRestoreThreshold() {
        assertThat(VoltageLoadBalancer.recoveryStages(190, 205, 12).map { it.floorV to it.ceilingA })
            .containsExactly(195 to 8, 200 to 10, 205 to 12).inOrder()
        // With the minimum 10 V gap the first stage starts right at the low threshold.
        assertThat(VoltageLoadBalancer.recoveryStages(195, 205, 16).first().floorV).isEqualTo(195)
    }

    @Test
    fun evaluate_voltageRecovery_stepsUpAfterFiveTicks() {
        val balancer = VoltageLoadBalancer(highThresholdV = 205, maxAmperage = 12)
        
        // Ticks 1..4 above 205V: no change
        for (i in 1..4) {
            assertThat(balancer.evaluate(210, 6)).isNull()
        }

        // 5th tick: steps up by 2A (6A -> 8A)
        assertThat(balancer.evaluate(210, 6)).isEqualTo(8)

        // Next 4 ticks: no change
        for (i in 1..4) {
            assertThat(balancer.evaluate(215, 8)).isNull()
        }

        // 5th tick: steps up (8A -> 10A)
        assertThat(balancer.evaluate(215, 8)).isEqualTo(10)

        // Next 5 ticks: steps up to max (10A -> 12A)
        for (i in 1..4) {
            balancer.evaluate(215, 10)
        }
        assertThat(balancer.evaluate(215, 10)).isEqualTo(12)

        // At max (12A): does not exceed maxAmperage
        for (i in 1..5) {
            balancer.evaluate(220, 12)
        }
        assertThat(balancer.evaluate(220, 12)).isNull()
    }

    @Test
    fun evaluate_voltageFluctuation_resetsCounters() {
        val balancer = VoltageLoadBalancer(lowThresholdV = 185, highThresholdV = 205)
        
        // 1 tick low, then 1 tick normal
        balancer.evaluate(180, 10)
        balancer.evaluate(195, 10) // resets low counter
        
        // Next 1 tick low should not trigger
        assertThat(balancer.evaluate(180, 10)).isNull()
    }

    @Test
    fun evaluate_invalidThresholdOrder_returnsNullSafely() {
        val inverted = VoltageLoadBalancer(lowThresholdV = 200, highThresholdV = 195)
        assertThat(inverted.evaluate(180, 12)).isNull()
        assertThat(inverted.evaluate(180, 12)).isNull()
        assertThat(inverted.evaluate(210, 8)).isNull()

        val equal = VoltageLoadBalancer(lowThresholdV = 200, highThresholdV = 200)
        assertThat(equal.evaluate(190, 12)).isNull()
        assertThat(equal.evaluate(190, 12)).isNull()
    }
}

