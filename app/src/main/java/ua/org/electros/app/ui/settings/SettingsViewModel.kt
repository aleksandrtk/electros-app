package ua.org.electros.app.ui.settings

import ua.org.electros.app.R
import ua.org.electros.app.ui.UiText
import ua.org.electros.app.ui.credentialsErrorText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.SettingsStore
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.repository.ChargeRepository

data class SettingsUiState(
    val deviceSerial: String,
    val password: String = "",
    val hasCredentials: Boolean,
    val isBatteryOptimizationIgnored: Boolean = true,
    val areNotificationsEnabled: Boolean = true,
    val isSavingCredentials: Boolean = false,
    val credentialsSaved: Boolean = false,
    val credentialsError: UiText? = null
)

class SettingsViewModel(
    private val authPreferences: AuthPreferences,
    private val repository: ChargeRepository? = null,
    private val settingsStore: SettingsStore? = null
) : ViewModel() {

    /** Whether a session is restarted by the app after a power outage; null when settings are unavailable. */
    val autoResumeAfterOutage: StateFlow<Boolean> = settingsStore?.settingsFlow
        ?.map { it.autoResumeAfterOutage }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsStore.currentSettings().autoResumeAfterOutage)
        ?: MutableStateFlow(true)

    val isWoodTheme: StateFlow<Boolean> = settingsStore?.settingsFlow
        ?.map { it.isWoodTheme }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsStore.currentSettings().isWoodTheme)
        ?: MutableStateFlow(false)

    fun setWoodTheme(enabled: Boolean) {
        settingsStore?.setWoodTheme(enabled)
    }

    val isWoodHiFi: StateFlow<Boolean> = settingsStore?.settingsFlow
        ?.map { it.isWoodHiFi }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsStore.currentSettings().isWoodHiFi)
        ?: MutableStateFlow(false)

    fun setAppearance(wood: Boolean, hiFi: Boolean) {
        settingsStore?.setAppearance(wood, hiFi)
    }

    fun setAutoResumeAfterOutage(enabled: Boolean) {
        settingsStore?.setAutoResumeAfterOutage(enabled)
    }

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            deviceSerial = authPreferences.deviceSerial,
            password = authPreferences.internetPassword,
            hasCredentials = authPreferences.hasValidCredentials()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun clearCredentialsFeedback() {
        val cur = _uiState.value
        if (cur.credentialsSaved || cur.credentialsError != null) {
            _uiState.value = cur.copy(credentialsSaved = false, credentialsError = null)
        }
    }

    fun updateCredentials(serial: String, pass: String, onSaved: () -> Unit = {}) {
        val trimmedSerial = serial.trim()
        val trimmedPass = pass.trim()
        if (trimmedSerial.isBlank() || trimmedPass.isBlank()) {
            _uiState.value = _uiState.value.copy(
                credentialsSaved = false,
                credentialsError = UiText.Res(R.string.settings_err_empty)
            )
            return
        }

        val repo = repository
        if (repo == null) {
            authPreferences.deviceSerial = trimmedSerial
            authPreferences.internetPassword = trimmedPass
            _uiState.value = _uiState.value.copy(
                deviceSerial = trimmedSerial,
                password = trimmedPass,
                hasCredentials = authPreferences.hasValidCredentials(),
                credentialsSaved = true,
                credentialsError = null
            )
            onSaved()
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSavingCredentials = true,
                credentialsSaved = false,
                credentialsError = null
            )
            val result = repo.login(trimmedSerial, trimmedPass)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    deviceSerial = trimmedSerial,
                    password = trimmedPass,
                    hasCredentials = authPreferences.hasValidCredentials(),
                    isSavingCredentials = false,
                    credentialsSaved = true,
                    credentialsError = null
                )
                onSaved()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isSavingCredentials = false,
                    credentialsSaved = false,
                    credentialsError = credentialsErrorText(err, R.string.settings_err_check)
                )
            }
        }
    }

    fun refreshBatteryOptimizationStatus(isIgnored: Boolean) {
        _uiState.value = _uiState.value.copy(isBatteryOptimizationIgnored = isIgnored)
    }

    fun refreshNotificationsStatus(isEnabled: Boolean) {
        _uiState.value = _uiState.value.copy(areNotificationsEnabled = isEnabled)
    }

    fun reloadCredentials() {
        _uiState.value = _uiState.value.copy(
            deviceSerial = authPreferences.deviceSerial,
            password = authPreferences.internetPassword,
            hasCredentials = authPreferences.hasValidCredentials(),
            isSavingCredentials = false,
            credentialsSaved = false,
            credentialsError = null
        )
    }

    fun logout(onComplete: () -> Unit = {}) {
        val repo = repository
        if (repo == null) {
            authPreferences.clearCredentials()
            _uiState.value = _uiState.value.copy(
                deviceSerial = "",
                password = "",
                hasCredentials = false,
                credentialsSaved = false,
                credentialsError = null
            )
            onComplete()
            return
        }
        viewModelScope.launch {
            val result = repo.logout()
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    credentialsError = UiText.Res(R.string.settings_err_logout)
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                deviceSerial = "",
                password = "",
                hasCredentials = false,
                credentialsSaved = false,
                credentialsError = null
            )
            onComplete()
        }
    }
}
