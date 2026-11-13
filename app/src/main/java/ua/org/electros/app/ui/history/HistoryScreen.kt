package ua.org.electros.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Історія та аналітика",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimaryDark
        )

        // Monthly Summary Hero Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = "СПОЖИВАННЯ ЗА МІСЯЦЬ", style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "%.1f кВт⋅год".format(state.currentMonthKwh),
                        style = MaterialTheme.typography.displayLarge,
                        fontSize = 32.sp,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = state.currentMonthLabel,
                        fontSize = 12.sp,
                        color = TextSecondaryDark
                    )
                }
            }
        }

        Text(
            text = "Останні сесії",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimaryDark
        )

        if (state.sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Історія сесій поки що порожня",
                    fontSize = 14.sp,
                    color = TextSecondaryDark
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionItemCard(session)
                }
            }
        }
    }
}

@Composable
private fun SessionItemCard(session: ChargeSessionEntity) {
    val dateFormatter = remember { SimpleDateFormat("dd.MM • HH:mm", Locale.getDefault()) }
    val formattedDate = remember(session.startedAt) { dateFormatter.format(Date(session.startedAt)) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = formattedDate, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimaryDark)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${session.amperageSetting}A • ${formatSessionStatus(session.status)}",
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }

            Text(
                text = "%.1f кВт⋅год".format(session.totalKwhDelivered),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryNeonGreen
            )
        }
    }
}

private fun formatSessionStatus(status: SessionStatus): String = when (status) {
    SessionStatus.TARGET_REACHED -> "Ліміт досягнуто"
    SessionStatus.VEHICLE_STOPPED -> "Завершено авто"
    SessionStatus.CANCELLED -> "Зупинено"
    SessionStatus.CHARGING -> "Заряджання"
    SessionStatus.OUTAGE_DETECTED,
    SessionStatus.OUTAGE_STABILIZING,
    SessionStatus.AUTO_RESUMING -> "Очікування світла"
    SessionStatus.STOP_PENDING -> "Зупинка..."
    SessionStatus.IDLE -> "Очікування"
}

