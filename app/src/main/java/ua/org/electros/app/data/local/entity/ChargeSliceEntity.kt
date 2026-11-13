package ua.org.electros.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SliceEndReason {
    POWER_OUTAGE,
    TARGET_REACHED,
    MANUAL_STOP,
    VEHICLE_STOPPED
}

@Entity(
    tableName = "charge_slices",
    foreignKeys = [
        ForeignKey(
            entity = ChargeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChargeSliceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val sliceIndex: Int,
    val stationCounterStartKwh: Double,
    val stationCounterLastKwh: Double,
    val finalizedKwh: Double = 0.0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val terminationReason: SliceEndReason? = null
)

