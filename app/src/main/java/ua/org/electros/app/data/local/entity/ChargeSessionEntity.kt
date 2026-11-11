package ua.org.electros.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SessionStatus {
    IDLE,
    CHARGING,
    OUTAGE_DETECTED,
    OUTAGE_STABILIZING,
    AUTO_RESUMING,
    STOP_PENDING,
    TARGET_REACHED,
    VEHICLE_STOPPED,
    CANCELLED
}

@Entity(tableName = "charge_sessions")
data class ChargeSessionEntity(
    @PrimaryKey
    val id: String,
    val targetKwh: Double,
    val amperageSetting: Int,
    val status: SessionStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val totalKwhDelivered: Double = 0.0,
    val targetDurationMinutes: Int = 0,
    val accumulatedChargingMs: Long = 0L,
    val lastTickAt: Long = 0L
)

