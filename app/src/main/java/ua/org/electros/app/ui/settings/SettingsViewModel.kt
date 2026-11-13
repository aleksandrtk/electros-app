package ua.org.electros.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.model.StationConfig
import ua.org.electros.app.data.repository.ChargeRepository

data class SettingsUiState(
    val deviceSerial: String,
    val hasCredentials: Boolean,
    val config: StationConfig = StationConfig(),
    val isBatteryOptimizationIgnored: Boolean = true,
    val areNotificationsEnabled: Boolean = true,
    val isSavingCredentials: Boolean = false,
    val credentialsSaved: Boolean = false,
    val credentialsError: String? = null
)

class SettingsViewModel(
    private val authPreferences: AuthPreferences,
    private val repository: ChargeRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            deviceSerial = authPreferences.deviceSerial,
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
                credentialsError = "Введіть номер пристрою та пароль для збереження"
            )
            return
        }

        val repo = repository
        if (repo == null) {
            authPreferences.deviceSerial = trimmedSerial
            authPreferences.internetPassword = trimmedPass
            _uiState.value = _uiState.value.copy(
                deviceSerial = trimmedSerial,
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
                    credentialsError = err.message ?: "Помилка перевірки даних на electros.org.ua"
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
                hasCredentials = false,
                credentialsSaved = false,
                credentialsError = null
            )
            onComplete()
            return
        }
        viewModelScope.launch {
            repo.logout()
            _uiState.value = _uiState.value.copy(
                deviceSerial = "",
                hasCredentials = false,
                credentialsSaved = false,
                credentialsError = null
            )
            onComplete()
        }
    }
}
