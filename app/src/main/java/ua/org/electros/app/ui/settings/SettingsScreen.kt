package ua.org.electros.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.org.electros.app.ui.theme.*

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var serialInput by remember(state.deviceSerial) { mutableStateOf(state.deviceSerial) }
    var passwordInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Налаштування",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimaryDark
        )

        // Station Credentials Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "АВТОРИЗАЦІЯ СТАНЦІЇ", style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)

                OutlinedTextField(
                    value = serialInput,
                    onValueChange = { serialInput = it },
                    label = { Text("Номер виробу (Серійник)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedBorderColor = PrimaryNeonGreen,
                        unfocusedBorderColor = OutlineDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Пароль інтернет") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedBorderColor = PrimaryNeonGreen,
                        unfocusedBorderColor = OutlineDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { viewModel.updateCredentials(serialInput, passwordInput) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen, contentColor = OnPrimaryDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Зберегти облікові дані", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Hardware Parameters Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "АПАРАТНІ ПАРАМЕТРИ", style = MaterialTheme.typography.labelSmall, color = SecondaryCyan)

                SettingRow(label = "Контроль заземлення", value = "Увімкнено")
                SettingRow(label = "Струм витоку (УЗО)", value = state.config.leakageCurrentThreshold)
                SettingRow(label = "Мін. напруга заряджання", value = "${state.config.minChargingVoltage} В")
                SettingRow(label = "Сервер станції", value = "${state.config.serverHost}:${state.config.serverPort}")
            }
        }

        // Background Service Exemption Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "ФОНОВА СЛУЖБА ANDROID", style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)
                Text(
                    text = "Оптимізація батареї: Вимкнено",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = PrimaryNeonGreen
                )
                Text(
                    text = "Служба ChargeMonitorService використовує спеціальний тип foregroundServiceType=specialUse для безперервного обліку кВт⋅год під час сну смартфона.",
                    fontSize = 12.sp,
                    color = TextSecondaryDark,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = TextPrimaryDark)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextSecondaryDark)
    }
}

