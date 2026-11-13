package ua.org.electros.app.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ua.org.electros.app.BuildConfig
import ua.org.electros.app.ota.OtaViewModel
import ua.org.electros.app.ui.theme.*

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    otaViewModel: OtaViewModel? = null,
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var serialInput by remember(state.deviceSerial) { mutableStateOf(state.deviceSerial) }
    var passwordInput by remember { mutableStateOf("") }
    var isSerialVisible by remember { mutableStateOf(false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    fun checkSystemStatuses() {
        val pm = context.getSystemService(PowerManager::class.java)
        viewModel.refreshBatteryOptimizationStatus(
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        val notificationsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED && nm?.areNotificationsEnabled() == true
        viewModel.refreshNotificationsStatus(notificationsGranted)
    }

    LaunchedEffect(Unit) {
        checkSystemStatuses()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkSystemStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                        viewModel.clearCredentialsFeedback()
                    },
                    label = { Text("Номер пристрою (наприклад, a1234)") },
                    singleLine = true,
                    visualTransformation = if (isSerialVisible) VisualTransformation.None else SerialMaskVisualTransformation,
                    trailingIcon = {
                        TextButton(onClick = { isSerialVisible = !isSerialVisible }) {
                            Text(
                                text = if (isSerialVisible) "Приховати" else "Показати",
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

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = {
                        passwordInput = it
                        viewModel.clearCredentialsFeedback()
                    },
                    label = { Text("Новий пароль пристрою") },
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

                if (!state.credentialsError.isNullOrBlank()) {
                    Text(
                        text = state.credentialsError.orEmpty(),
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        viewModel.updateCredentials(serialInput, passwordInput) {
                            passwordInput = ""
                        }
                    },
                    enabled = !state.isSavingCredentials &&
                        serialInput.isNotBlank() &&
                        passwordInput.isNotBlank(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen, contentColor = OnPrimaryDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when {
                            state.isSavingCredentials -> "Перевірка..."
                            state.credentialsSaved -> "✓ Збережено"
                            else -> "Зберегти нові дані"
                        },
                        fontWeight = FontWeight.Bold
                    )
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

        // Background Monitoring / Doze Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "ФОНОВИЙ МОНІТОРИНГ (DOZE)", style = MaterialTheme.typography.labelSmall, color = WarningAmber)

                SettingRow(
                    label = "Обмеження батареї",
                    value = if (state.isBatteryOptimizationIgnored) "Вимкнено ✓" else "Активні (Doze) ⚠️"
                )

                Text(
                    text = if (state.isBatteryOptimizationIgnored) {
                        "Додаток може стабільно опитувати станцію кожні 10 секунд при вимкненому екрані, щоб вчасно спрацювали ліміт кВт⋅год і таймер."
                    } else {
                        "У режимі сну Android затримує фонові опитування. Дозвольте роботу без обмежень батареї для точної зупинки за лімітом."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    lineHeight = 17.sp
                )

                if (!state.isBatteryOptimizationIgnored) {
                    Button(
                        onClick = {
                            val reqIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(reqIntent) }.onFailure {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber, contentColor = OnPrimaryDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Вимкнути оптимізацію батареї", fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = OutlineDark)

                SettingRow(
                    label = "Сповіщення (аварії та ліміт)",
                    value = if (state.areNotificationsEnabled) "Дозволено ✓" else "Вимкнено ⚠️"
                )

                if (!state.areNotificationsEnabled) {
                    Text(
                        text = "Без дозволу на сповіщення ви не отримаєте попередження про зникнення світла, завершення квоти або помилку зупинки станції.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        lineHeight = 17.sp
                    )
                    Button(
                        onClick = {
                            val notifIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(notifIntent)
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber, contentColor = OnPrimaryDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Увімкнути сповіщення", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // App Updates Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "ОНОВЛЕННЯ ДОДАТКА", style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)

                SettingRow(label = "Поточна версія", value = "v${BuildConfig.VERSION_NAME}")

                Button(
                    onClick = { otaViewModel?.checkForUpdates(manual = true) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen, contentColor = OnPrimaryDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Перевірити оновлення", fontWeight = FontWeight.Bold)
                }
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

private object SerialMaskVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val visiblePrefix = when {
            raw.length <= 2 -> 0
            raw.length <= 5 -> 2
            else -> 4
        }
        val masked = buildString(raw.length) {
            for (i in raw.indices) {
                append(if (i < visiblePrefix) raw[i] else '•')
            }
        }
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}
