package ua.org.electros.app.ui.dashboard

import kotlin.math.abs
import kotlin.math.sign

/**
 * Geometry of the rotary current selector: hard end stops and magnetic detents.
 *
 * While dragging, the finger angle is mapped through [detentAngle]: inside [DETENT_DEADZONE_DEG] of a
 * stop the knob stays parked on it, then it travels faster to the midpoint between stops, so each
 * position is felt as a distinct click instead of a free spin. The mapping is continuous, so the knob
 * never jumps.
 */
internal object KnobPhysics {
    val STOP_ANGLES: List<Float> = listOf(-114f, -58f, 0f, 58f, 114f)
    const val DETENT_DEADZONE_DEG = 9f

    /** Hard mechanical stops: the knob cannot be wound past the first or last position. */
    fun clampToTravel(rawAngle: Float): Float =
        rawAngle.coerceIn(STOP_ANGLES.first(), STOP_ANGLES.last())

    fun nearestStopIndex(angle: Float): Int =
        STOP_ANGLES.indices.minBy { abs(angle - STOP_ANGLES[it]) }

    fun detentAngle(rawAngle: Float): Float {
        val angle = clampToTravel(rawAngle)
        val idx = nearestStopIndex(angle)
        val stop = STOP_ANGLES[idx]
        val offset = angle - stop
        if (abs(offset) <= DETENT_DEADZONE_DEG) return stop

        val neighbor = if (offset > 0f) idx + 1 else idx - 1
        if (neighbor !in STOP_ANGLES.indices) return stop
        val half = abs(STOP_ANGLES[neighbor] - stop) / 2f
        val t = ((abs(offset) - DETENT_DEADZONE_DEG) / (half - DETENT_DEADZONE_DEG)).coerceIn(0f, 1f)
        return stop + sign(offset) * half * t
    }

    /** True when the knob is parked inside the detent of [index] (used to fire the click haptic). */
    fun isSeatedIn(angle: Float, index: Int): Boolean =
        abs(angle - STOP_ANGLES[index]) <= 0.01f
}
