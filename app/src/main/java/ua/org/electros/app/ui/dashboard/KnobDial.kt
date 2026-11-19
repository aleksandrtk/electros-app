package ua.org.electros.app.ui.dashboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The current selector's rotating dial: drag to turn with magnetic detents, tap the left or right
 * half to step. Both dashboard looks use it and only differ in [draw], which paints the knob at
 * the given angle.
 */
@Composable
fun KnobDial(
    selectedAmperes: Int,
    onAmperageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    draw: DrawScope.(angle: Float) -> Unit
) {
    val stops = listOf(6, 8, 10, 12, 16)
    val view = LocalView.current
    val currentIndex = stops.indices.minByOrNull { idx ->
        kotlin.math.abs(stops[idx] - selectedAmperes)
    } ?: 2

    val stopAngles = KnobPhysics.STOP_ANGLES
    fun angleForIndex(idx: Int) = stopAngles[idx.coerceIn(0, stopAngles.lastIndex)]
    val targetAngle = angleForIndex(currentIndex)
    // Read inside the long-lived gesture handler so a value change does not restart a drag in progress.
    val latestIndex by rememberUpdatedState(currentIndex)

    // Heavy, critically damped settle: the knob glides into the detent and stops, no overshoot or wobble.
    val settleSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f)
    }
    val knobAngle = remember { Animatable(targetAngle) }
    val knobScope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragAngle by remember { mutableFloatStateOf(targetAngle) }
    var lastHapticIndex by remember { mutableIntStateOf(currentIndex) }

    // Follow value changes made elsewhere (label tap, half-tap, station sync), never during a drag.
    LaunchedEffect(targetAngle) {
        if (!isDragging) knobAngle.animateTo(targetAngle, settleSpec)
    }

    /** Hands the knob from the finger back to the animation without a visual jump. */
    fun settleFrom(fromAngle: Float, toIndex: Int) {
        knobScope.launch(start = CoroutineStart.UNDISPATCHED) {
            knobAngle.snapTo(fromAngle)
            knobAngle.animateTo(angleForIndex(toIndex), settleSpec)
        }
    }

    val currentAngle = if (isDragging) dragAngle else knobAngle.value

    Box(
        modifier = modifier
            .pointerInput(stops) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = size.width * 0.48f
                    val startDist = hypot(down.position.x - cx, down.position.y - cy)

                    // Only interact if the touch starts inside the circular knob well
                    if (startDist > radius) return@awaitEachGesture

                    var isDragStarted = false
                    var totalDragMovement = 0f
                    val startPos = down.position
                    var lastTouchAngle = Math.toDegrees(atan2((startPos.x - cx).toDouble(), -(startPos.y - cy).toDouble())).toFloat()
                    var currentKnobAngle = knobAngle.value

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            // Finger released
                            if (isDragStarted) {
                                change.consume()
                                val finalIdx = KnobPhysics.nearestStopIndex(dragAngle)
                                settleFrom(dragAngle, finalIdx)
                                isDragging = false
                                if (finalIdx != latestIndex) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    onAmperageSelected(stops[finalIdx])
                                }
                            } else if (totalDragMovement < 8.dp.toPx()) {
                                // Tap on knob: left half decreases, right half increases
                                change.consume()
                                val isRightSide = startPos.x >= cx
                                val newIdx = if (isRightSide) {
                                    (latestIndex + 1).coerceAtMost(stops.lastIndex)
                                } else {
                                    (latestIndex - 1).coerceAtLeast(0)
                                }
                                if (newIdx != latestIndex) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    onAmperageSelected(stops[newIdx])
                                }
                            }
                            break
                        }

                        val curPos = change.position
                        val dx = curPos.x - cx
                        val dy = curPos.y - cy
                        val distFromStart = hypot(curPos.x - startPos.x, curPos.y - startPos.y)
                        totalDragMovement += distFromStart

                        if (!isDragStarted && distFromStart > 6.dp.toPx()) {
                            isDragStarted = true
                            // Pick the knob up exactly where it is, even mid-animation.
                            currentKnobAngle = knobAngle.value
                            dragAngle = currentKnobAngle
                            lastHapticIndex = KnobPhysics.nearestStopIndex(currentKnobAngle)
                            isDragging = true
                            knobScope.launch { knobAngle.stop() }
                        }

                        if (isDragStarted) {
                            change.consume()
                            val currentTouchAngle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                            var delta = currentTouchAngle - lastTouchAngle
                            if (delta > 180f) delta -= 360f
                            if (delta < -180f) delta += 360f
                            lastTouchAngle = currentTouchAngle

                            // Hard mechanical end stops: no rubber-band over-travel to spring back from.
                            currentKnobAngle = KnobPhysics.clampToTravel(currentKnobAngle + delta)

                            // Magnetic detents: parks on each position, then moves on to the next.
                            dragAngle = KnobPhysics.detentAngle(currentKnobAngle)

                            val closestIdx = KnobPhysics.nearestStopIndex(dragAngle)
                            if (closestIdx != lastHapticIndex && KnobPhysics.isSeatedIn(dragAngle, closestIdx)) {
                                lastHapticIndex = closestIdx
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                    if (isDragging) {
                        // Gesture cancelled mid-drag: glide back to the committed position.
                        settleFrom(dragAngle, latestIndex)
                        isDragging = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) { draw(currentAngle) }
    }
}
