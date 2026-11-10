package ua.org.electros.app.data.local

import androidx.room.TypeConverter
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason

class Converters {
    @TypeConverter
    fun fromSessionStatus(status: SessionStatus): String = status.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus = try {
        SessionStatus.valueOf(value)
    } catch (e: Exception) {
        SessionStatus.IDLE
    }

    @TypeConverter
    fun fromSliceEndReason(reason: SliceEndReason): String = reason.name

    @TypeConverter
    fun toSliceEndReason(value: String): SliceEndReason = try {
        SliceEndReason.valueOf(value)
    } catch (e: Exception) {
        SliceEndReason.MANUAL_STOP
    }
}

