package ua.org.electros.app.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KnobPhysicsTest {

    @Test
    fun insideDeadzone_knobStaysParkedOnStop() {
        assertThat(KnobPhysics.detentAngle(0f)).isEqualTo(0f)
        assertThat(KnobPhysics.detentAngle(8f)).isEqualTo(0f)
        assertThat(KnobPhysics.detentAngle(-8f)).isEqualTo(0f)
        assertThat(KnobPhysics.detentAngle(64f)).isEqualTo(58f)
    }

    @Test
    fun mappingIsContinuousAtMidpointBetweenStops() {
        val midpoint = 29f
        assertThat(KnobPhysics.detentAngle(midpoint - 0.001f)).isWithin(0.01f).of(midpoint)
        assertThat(KnobPhysics.detentAngle(midpoint + 0.001f)).isWithin(0.01f).of(midpoint)
    }

    @Test
    fun mappingIsMonotonicAcrossWholeTravel() {
        var previous = KnobPhysics.detentAngle(-130f)
        var raw = -130f
        while (raw <= 130f) {
            val mapped = KnobPhysics.detentAngle(raw)
            assertThat(mapped).isAtLeast(previous)
            previous = mapped
            raw += 0.25f
        }
    }

    @Test
    fun hardEndStops_noOverTravel() {
        assertThat(KnobPhysics.detentAngle(170f)).isEqualTo(114f)
        assertThat(KnobPhysics.detentAngle(-170f)).isEqualTo(-114f)
    }

    @Test
    fun nearestStop_selectsClosestPosition() {
        assertThat(KnobPhysics.nearestStopIndex(25f)).isEqualTo(2)
        assertThat(KnobPhysics.nearestStopIndex(31f)).isEqualTo(3)
        assertThat(KnobPhysics.nearestStopIndex(-200f)).isEqualTo(0)
    }
}
