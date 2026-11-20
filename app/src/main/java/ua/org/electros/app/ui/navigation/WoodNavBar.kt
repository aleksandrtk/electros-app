package ua.org.electros.app.ui.navigation

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.org.electros.app.ui.dashboard.Manrope

private val DockShape = RoundedCornerShape(28.dp)
private val Amber = Color(0xFFFFB347)
private val Idle = Color(0xFFC4A383)

/**
 * The bottom bar of the wood looks: a floating dock of smoked glass over the walnut, with the
 * current tab lit by an amber pill, in the spirit of the voltmeter window on the main screen.
 */
@Composable
internal fun WoodNavBar(current: NavTab, onSelect: (NavTab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .shadow(18.dp, DockShape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(
                    Brush.verticalGradient(listOf(Color(0xF22A1A10), Color(0xF7110A06))),
                    DockShape
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color(0x70F4C58A), Color(0x14F4C58A))),
                    DockShape
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEach { tab ->
                DockItem(tab, selected = tab == current, onClick = { onSelect(tab) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DockItem(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val view = LocalView.current
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "lit")
    val iconColor by animateColorAsState(if (selected) Color(0xFF2A1406) else Idle, tween(220), label = "icon")
    val textColor by animateColorAsState(if (selected) Amber else Idle.copy(alpha = 0.85f), tween(220), label = "text")
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!selected) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = 60.dp, height = 34.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = lit
                        scaleX = 0.6f + 0.4f * lit
                    }
                    .shadow(10.dp, RoundedCornerShape(17.dp), ambientColor = Amber, spotColor = Amber)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFFFC66E), Color(0xFFE88A26))),
                        RoundedCornerShape(17.dp)
                    )
            )
            Icon(
                painter = painterResource(tab.iconRes),
                contentDescription = stringResource(tab.titleRes),
                tint = iconColor,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(tab.titleRes),
            style = TextStyle(
                fontFamily = Manrope,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 0.2.sp,
                color = textColor
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Lets the screen above melt into the walnut over the last few dp instead of a hard cut at the dock. */
internal fun Modifier.fadeIntoDock(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = 28.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height
            ),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - fade),
            blendMode = BlendMode.DstIn
        )
    }
