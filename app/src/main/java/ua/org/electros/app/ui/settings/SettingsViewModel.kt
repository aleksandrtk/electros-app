package ua.org.electros.app.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.model.StationConfig
import ua.org.electros.app.data.repository.ChargeRepository

data class SettingsUiState(
    val deviceSerial: String,
    val hasCredentials: Boolean,
    val config: StationConfig = StationConfig(),
    val isBatteryOptimizationIgnored: Boolean = true
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

    fun updateCredentials(serial: String, pass: String) {
        authPreferences.deviceSerial = serial
        authPreferences.internetPassword = pass
        _uiState.value = _uiState.value.copy(
            deviceSerial = serial,
            hasCredentials = authPreferences.hasValidCredentials()
        )
    }

    fun refreshBatteryOptimizationStatus(isIgnored: Boolean) {
        _uiState.value = _uiState.value.copy(isBatteryOptimizationIgnored = isIgnored)
    }

    fun logout() {
        repository?.logout() ?: authPreferences.clearCredentials()
        _uiState.value = _uiState.value.copy(
            deviceSerial = "",
            hasCredentials = false
        )
    }
}
