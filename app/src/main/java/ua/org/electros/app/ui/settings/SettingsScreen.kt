package ua.org.electros.app.ui.settings

import ua.org.electros.app.ui.AppLanguage
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import ua.org.electros.app.R
import ua.org.electros.app.ui.asString
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ua.org.electros.app.BuildConfig
import ua.org.electros.app.ota.OtaViewModel
import ua.org.electros.app.service.ChargeMonitorService
import ua.org.electros.app.ui.theme.*
import android.text.format.Formatter
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.org.electros.app.diag.DiagExport
import ua.org.electros.app.diag.DiagLog

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    otaViewModel: OtaViewModel? = null,
    onLogout: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var serialInput by rememberSaveable(state.deviceSerial) { mutableStateOf(state.deviceSerial) }
    // Prefilled with what is saved: the number is shown in full, the password stays hidden until
    // "Show" and a fingerprint or screen-lock check. Not saveable, so it hides again on leaving.
    var passwordInput by rememberSaveable(state.password) { mutableStateOf(state.password) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }

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
        viewModel.reloadCredentials()
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
            .screenBackground()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimaryDark
        )

        // Station Credentials Card
        ThemedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.settings_station), style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)

                OutlinedTextField(
                    value = serialInput,
                    onValueChange = {
                        serialInput = it
                        viewModel.clearCredentialsFeedback()
                    },
                    label = { Text(stringResource(R.string.settings_serial_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false
                    ),
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
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false
                    ),
                    trailingIcon = {
                        TextButton(onClick = {
                            when {
                                isPasswordVisible -> isPasswordVisible = false
                                // Nothing saved yet: what is typed is the user's own fresh input.
                                state.password.isEmpty() -> isPasswordVisible = true
                                else -> DeviceAuth.authenticate(
                                    context,
                                    onSuccess = { isPasswordVisible = true },
                                    onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                                )
                            }
                        }) {
                            Text(
                                text = stringResource(if (isPasswordVisible) R.string.hide else R.string.show),
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

                if (state.credentialsError != null) {
                    Text(
                        text = state.credentialsError?.asString().orEmpty(),
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                ThemedButton(
                    onClick = {
                        viewModel.updateCredentials(serialInput, passwordInput)
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
                            state.isSavingCredentials -> stringResource(R.string.settings_checking)
                            state.credentialsSaved -> stringResource(R.string.settings_saved)
                            else -> stringResource(R.string.settings_save_credentials)
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                if (showLogoutConfirm) {
                    AlertDialog(
                        onDismissRequest = { showLogoutConfirm = false },
                        title = { Text(stringResource(R.string.settings_logout_title)) },
                        text = { Text(stringResource(R.string.settings_logout_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showLogoutConfirm = false
                                viewModel.logout {
                                    ChargeMonitorService.stop(context)
                                    onLogout()
                                }
                            }) { Text(stringResource(R.string.settings_logout_confirm), color = ErrorRed) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }

                ThemedButton(
                    outlined = true,
                    danger = true,
                    onClick = { showLogoutConfirm = true },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_logout), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Charging behaviour Card
        val autoResume by viewModel.autoResumeAfterOutage.collectAsState()
        ThemedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = stringResource(R.string.settings_charging), style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_auto_resume),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimaryDark,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoResume,
                        onCheckedChange = { viewModel.setAutoResumeAfterOutage(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPrimaryDark,
                            checkedTrackColor = PrimaryNeonGreen
                        )
                    )
                }
                Text(
                    text = stringResource(R.string.settings_auto_resume_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    lineHeight = 17.sp
                )
            }
        }

        // Appearance Card
        val isWood by viewModel.isWoodTheme.collectAsState()
        val isHiFi by viewModel.isWoodHiFi.collectAsState()
        ThemedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = stringResource(R.string.settings_appearance), style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceContainerHighDark)
                        .padding(3.dp)
                ) {
                    listOf(
                        Triple(false, false, R.string.settings_theme_classic),
                        Triple(true, false, R.string.settings_theme_wood),
                        Triple(true, true, R.string.settings_theme_wood_hifi)
                    ).forEach { (wood, hiFi, label) ->
                        val isSelected = isWood == wood && (!wood || isHiFi == hiFi)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) SurfaceContainerHighestDark else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { if (!isSelected) viewModel.setAppearance(wood, hiFi) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(label),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) TextPrimaryDark else TextSecondaryDark,
                                maxLines = 1
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.settings_wood_theme_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    lineHeight = 17.sp
                )
            }
        }

        // Language Card
        LanguageCard()

        // Background Monitoring / Doze Card
        ThemedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = stringResource(R.string.settings_background), style = MaterialTheme.typography.labelSmall, color = WarningAmber)

                SettingRow(
                    label = stringResource(R.string.settings_battery),
                    value = stringResource(if (state.isBatteryOptimizationIgnored) R.string.settings_battery_off else R.string.settings_battery_on)
                )

                Text(
                    text = if (state.isBatteryOptimizationIgnored) {
                        stringResource(R.string.settings_battery_ok_hint)
                    } else {
                        stringResource(R.string.settings_battery_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    lineHeight = 17.sp
                )

                if (!state.isBatteryOptimizationIgnored) {
                    ThemedButton(
                        onClick = {
                            val reqIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                            runCatching { context.startActivity(reqIntent) }.onFailure {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber, contentColor = OnPrimaryDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_battery_button), fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = OutlineDark)

                SettingRow(
                    label = stringResource(R.string.settings_notifications),
                    value = stringResource(if (state.areNotificationsEnabled) R.string.settings_notifications_on else R.string.settings_notifications_off)
                )

                if (!state.areNotificationsEnabled) {
                    Text(
                        text = stringResource(R.string.settings_notifications_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        lineHeight = 17.sp
                    )
                    ThemedButton(
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
                        Text(stringResource(R.string.settings_notifications_button), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        DiagnosticLogCard()

        // App Updates Card
        ThemedCard(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.settings_updates), style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)

                SettingRow(label = stringResource(R.string.settings_version), value = "v${BuildConfig.VERSION_NAME}")

                ThemedButton(
                    onClick = { otaViewModel?.checkForUpdates(manual = true) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen, contentColor = OnPrimaryDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_check_updates), fontWeight = FontWeight.Bold)
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

@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppLanguage.current(context)) }
    ThemedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.settings_language), style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceContainerHighDark)
                    .padding(3.dp)
            ) {
                listOf(
                    AppLanguage.UKRAINIAN to "Українська",
                    AppLanguage.ENGLISH to "English"
                ).forEach { (language, label) ->
                    val isSelected = selected == language
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) SurfaceContainerHighestDark else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {
                                if (!isSelected) {
                                    selected = language
                                    AppLanguage.apply(context, language)
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) TextPrimaryDark else TextSecondaryDark
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(DiagLog.isEnabled) }
    var sizeBytes by remember { mutableLongStateOf(0L) }
    var isExporting by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    suspend fun refreshSize() {
        sizeBytes = withContext(Dispatchers.IO) { DiagLog.sizeBytes() }
    }
    LaunchedEffect(enabled) {
        while (true) {
            refreshSize()
            kotlinx.coroutines.delay(5_000L)
        }
    }

    ThemedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.settings_log), style = MaterialTheme.typography.labelSmall, color = PrimaryNeonGreen)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_log_switch),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        DiagLog.setEnabled(it)
                        enabled = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnPrimaryDark,
                        checkedTrackColor = PrimaryNeonGreen
                    )
                )
            }

            Text(
                text = stringResource(R.string.settings_log_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark,
                lineHeight = 17.sp
            )

            SettingRow(
                label = stringResource(R.string.settings_log_size),
                value = Formatter.formatShortFileSize(context, sizeBytes)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ThemedButton(
                    onClick = {
                        isExporting = true
                        scope.launch {
                            val intent = runCatching { DiagExport.buildShareIntent(context) }
                                .onFailure { DiagLog.e("Journal", "Export failed", it) }
                                .getOrNull()
                            isExporting = false
                            refreshSize()
                            if (intent == null) {
                                Toast.makeText(context, R.string.settings_log_empty, Toast.LENGTH_SHORT).show()
                            } else {
                                runCatching { context.startActivity(intent) }
                            }
                        }
                    },
                    enabled = !isExporting && sizeBytes > 0L,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen, contentColor = OnPrimaryDark),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.settings_log_export), fontWeight = FontWeight.Bold)
                }
                ThemedButton(
                    outlined = true,
                    onClick = { showClearConfirm = true },
                    enabled = sizeBytes > 0L,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondaryDark)
                ) {
                    Text(stringResource(R.string.settings_log_clear))
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_log_clear_title)) },
            text = { Text(stringResource(R.string.settings_log_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { DiagLog.clear() }
                        refreshSize()
                    }
                }) { Text(stringResource(R.string.settings_log_clear), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
