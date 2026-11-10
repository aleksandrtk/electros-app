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
    fun evaluate_criticalVoltageDrop_immediatelyDropsToMinAmperage() {
        val balancer = VoltageLoadBalancer(lowThresholdV = 185, minAmperage = 6)
        // Voltage critically low (< 175V)
        val result = balancer.evaluate(172, 12)
        assertThat(result).isEqualTo(6)
    }

    @Test
    fun evaluate_deadband_maintainsCurrentAmperage() {
        val balancer = VoltageLoadBalancer(lowThresholdV = 185, highThresholdV = 205)
        for (v in 185..205) {
            assertThat(balancer.evaluate(v, 8)).isNull()
        }
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
}

