package ua.org.electros.app.ui.ota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ua.org.electros.app.BuildConfig
import ua.org.electros.app.ota.OtaViewModel
import ua.org.electros.app.ui.theme.*
import java.util.Locale

@Composable
fun UpdateDialog(viewModel: OtaViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isChecking by viewModel.isCheckingUpdate.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val isDownloading by viewModel.isDownloadingUpdate.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val updateError by viewModel.updateError.collectAsState()
    val awaitingPermission by viewModel.awaitingInstallPermission.collectAsState()

    DisposableEffect(lifecycleOwner, awaitingPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingPermission) {
                viewModel.resumePendingInstallIfPermitted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) viewModel.dismissUpdateDialog()
        },
        containerColor = SurfaceContainerDark,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (updateInfo?.hasUpdate == true) {
                            "Оновлення доступне"
                        } else if (isChecking) {
                            "Перевірка оновлень..."
                        } else {
                            "Остання версія"
                        },
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (!isDownloading) {
                    IconButton(onClick = { viewModel.dismissUpdateDialog() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрити",
                            tint = TextSecondaryDark
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isChecking) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = PrimaryNeonGreen)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Перевірка GitHub Releases...",
                            color = TextSecondaryDark,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (updateError != null) {
                    Text(
                        text = "Помилка перевірки оновлень:\n$updateError",
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (updateInfo?.hasUpdate == true) {
                    val info = updateInfo!!
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = "Поточна: v${BuildConfig.VERSION_NAME}",
                            color = TextSecondaryDark,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrimaryNeonGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Нова: ${info.rawTagName}",
                                color = PrimaryNeonGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (info.releaseName.isNotEmpty() && info.releaseName != info.rawTagName) {
                        Text(
                            text = info.releaseName,
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    Text(
                        text = "Список змін:",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDark)
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = info.changelog.ifEmpty { "Покращення стабільності та оптимізація." },
                            color = TextSecondaryDark,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = PrimaryNeonGreen,
                            trackColor = OutlineDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = String.format(Locale.getDefault(), "Завантаження оновлення: %.0f%%", downloadProgress * 100),
                            color = PrimaryNeonGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = "У вас встановлена найновіша версія Electros EV (v${BuildConfig.VERSION_NAME}). Нових релізів на GitHub немає.",
                        color = TextSecondaryDark,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            if (updateInfo?.hasUpdate == true && !isChecking) {
                Button(
                    onClick = { viewModel.startDownloadAndInstall(context) },
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryNeonGreen,
                        contentColor = OnPrimaryDark
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            color = OnPrimaryDark,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Завантаження...", fontWeight = FontWeight.Bold)
                    } else if (awaitingPermission) {
                        Text("Встановити завантажений APK", fontWeight = FontWeight.Bold)
                    } else if (updateInfo?.downloadUrl != null) {
                        Text("Завантажити та встановити", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Відкрити сторінку релізу", fontWeight = FontWeight.Bold)
                    }
                }
            } else if (!isChecking) {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text("OK", color = PrimaryNeonGreen, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (updateInfo?.hasUpdate == true && !isDownloading) {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text("Пізніше", color = TextSecondaryDark)
                }
            }
        }
    )
}

