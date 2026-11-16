package ua.org.electros.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.repository.ChargeRepository

data class LoginUiState(
    val deviceSerial: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class LoginViewModel(
    private val repository: ChargeRepository,
    private val authPreferences: AuthPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(
            deviceSerial = authPreferences.deviceSerial,
            password = authPreferences.internetPassword
        )
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onSerialChanged(serial: String) {
        _uiState.value = _uiState.value.copy(deviceSerial = serial, errorMessage = null)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    fun reloadFromPreferences() {
        _uiState.value = LoginUiState(
            deviceSerial = authPreferences.deviceSerial,
            password = authPreferences.internetPassword
        )
    }

    fun login(onSuccess: () -> Unit) {
        val serial = _uiState.value.deviceSerial.trim()
        val password = _uiState.value.password.trim()

        if (serial.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Будь ласка, заповніть номер пристрою та пароль")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = repository.login(serial, password)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(password = "", isLoading = false)
                onSuccess()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Помилка авторизації на electros.org.ua"
                )
            }
        }
    }
}

