package ua.org.electros.app.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.org.electros.app.BuildConfig

class OtaViewModel : ViewModel() {
    private val tag = "OtaViewModel"

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateInfo = MutableStateFlow<OtaUpdater.UpdateInfo?>(null)
    val updateInfo: StateFlow<OtaUpdater.UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0.0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()

    fun checkForUpdates(currentVersion: String = BuildConfig.VERSION_NAME, manual: Boolean = true) {
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _updateError.value = null
            try {
                val info = OtaUpdater.checkForUpdates(currentVersion)
                _updateInfo.value = info
                if (manual || info.hasUpdate) {
                    _showUpdateDialog.value = true
                }
            } catch (e: Exception) {
                Log.e(tag, "Check for updates error: ${e.message}", e)
                _updateError.value = e.message ?: "Помилка перевірки оновлень"
                if (manual) {
                    _showUpdateDialog.value = true
                }
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    fun startDownloadAndInstall(context: Context) {
        val info = _updateInfo.value ?: return
        val downloadUrl = info.downloadUrl
        if (downloadUrl.isNullOrEmpty()) {
            if (!info.releaseHtmlUrl.isNullOrEmpty()) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseHtmlUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            }
            return
        }

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _downloadProgress.value = 0.0f
            _updateError.value = null

            val downloadedFile = OtaUpdater.downloadApk(
                context = context,
                downloadUrl = downloadUrl,
                onProgress = { progress ->
                    _downloadProgress.value = progress
                },
                expectedSha256 = info.expectedSha256,
                expectedSizeBytes = info.expectedSizeBytes
            )

            _isDownloadingUpdate.value = false

            if (downloadedFile != null && downloadedFile.exists()) {
                OtaUpdater.installApk(context, downloadedFile)
                _showUpdateDialog.value = false
            } else {
                _updateError.value = "Не вдалося завантажити оновлення або перевірити цілісність файлу"
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}

