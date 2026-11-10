package ua.org.electros.app.data.model

data class StationConfig(
    val isGroundingControlEnabled: Boolean = true,
    val leakageCurrentThreshold: String = "> 30мА",
    val minChargingVoltage: Int = 175,
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val serverHost: String = "electros.org.ua",
    val serverPort: Int = 8000,
    val deviceSerial: String = "",
    val voltageCalibrationConstant: Double = 0.01165,
    val currentCalibrationConstant: Double = 0.00155
)

