package ua.org.electros.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.org.electros.app.ui.theme.*

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var serialInput by remember(state.deviceSerial) { mutableStateOf(state.deviceSerial) }
    var passwordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var savedSuccess by remember { mutableStateOf(false) }

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
                Text(text = "ПІДКЛЮЧЕННЯ ДО СТАНЦІЇ", style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)

                OutlinedTextField(
                    value = serialInput,
                    onValueChange = {
                        serialInput = it
                        savedSuccess = false
                    },
                    label = { Text("Номер пристрою (наприклад, a1234)") },
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
                    onValueChange = {
                        passwordInput = it
                        savedSuccess = false
                    },
                    label = { Text("Пароль пристрою") },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Text(
                                text = if (isPasswordVisible) "Приховати" else "Показати",
                                color = TextSecondaryDark,
                                fontSize = 12.sp
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedBorderColor = PrimaryNeonGreen,
                        unfocusedBorderColor = OutlineDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        viewModel.updateCredentials(serialInput, passwordInput)
                        savedSuccess = true
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen, contentColor = OnPrimaryDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (savedSuccess) "✓ Збережено" else "Зберегти нові дані", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        viewModel.logout()
                        onLogout()
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Вийти зі станції", fontWeight = FontWeight.Bold)
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
                Text(text = "АПАРАТНІ ПАРАМЕТРИ СТАНЦІЇ", style = MaterialTheme.typography.labelSmall, color = SecondaryCyan)

                SettingRow(label = "Контроль заземлення", value = "Увімкнено")
                SettingRow(label = "Струм витоку (УЗО)", value = state.config.leakageCurrentThreshold)
                SettingRow(label = "Мін. напруга заряджання", value = "${state.config.minChargingVoltage} В")
                SettingRow(label = "Сервер станції", value = "${state.config.serverHost}:${state.config.serverPort}")
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
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondaryDark)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
    }
}
