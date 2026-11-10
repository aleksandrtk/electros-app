package ua.org.electros.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason

@Dao
interface ChargeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChargeSessionEntity)

    @Update
    suspend fun updateSession(session: ChargeSessionEntity)

    @Query("SELECT * FROM charge_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): ChargeSessionEntity?

    @Query("SELECT * FROM charge_sessions WHERE status IN ('CHARGING', 'OUTAGE_DETECTED', 'OUTAGE_STABILIZING', 'AUTO_RESUMING') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSession(): ChargeSessionEntity?

    @Query("SELECT * FROM charge_sessions WHERE status IN ('CHARGING', 'OUTAGE_DETECTED', 'OUTAGE_STABILIZING', 'AUTO_RESUMING') ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveSession(): Flow<ChargeSessionEntity?>

    @Query("SELECT * FROM charge_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<ChargeSessionEntity>>

    @Insert
    suspend fun insertSlice(slice: ChargeSliceEntity): Long

    @Update
    suspend fun updateSlice(slice: ChargeSliceEntity)

    @Query("SELECT * FROM charge_slices WHERE sessionId = :sessionId ORDER BY sliceIndex ASC")
    suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity>

    @Query("SELECT * FROM charge_slices WHERE sessionId = :sessionId ORDER BY sliceIndex DESC LIMIT 1")
    suspend fun getLatestSliceForSession(sessionId: String): ChargeSliceEntity?

    @Transaction
    suspend fun finalizeActiveSliceAndCommit(
        sessionId: String,
        lastSampledKwh: Double,
        reason: SliceEndReason,
        endTime: Long = System.currentTimeMillis()
    ) {
        val latestSlice = getLatestSliceForSession(sessionId) ?: return
        if (latestSlice.endedAt == null) {
            val delivered = (lastSampledKwh - latestSlice.stationCounterStartKwh).coerceAtLeast(0.0)
            val updated = latestSlice.copy(
                stationCounterLastKwh = lastSampledKwh,
                finalizedKwh = delivered,
                endedAt = endTime,
                terminationReason = reason
            )
            updateSlice(updated)
        }

        // Update session total delivered
        val slices = getSlicesForSession(sessionId)
        val sumKwh = slices.sumOf { it.finalizedKwh }
        val session = getSessionById(sessionId)
        if (session != null) {
            updateSession(session.copy(totalKwhDelivered = sumKwh))
        }
    }

    @Transaction
    suspend fun startNewSlice(
        sessionId: String,
        stationCounterCurrentKwh: Double,
        startTime: Long = System.currentTimeMillis()
    ) {
        val latestSlice = getLatestSliceForSession(sessionId)
        val nextIndex = (latestSlice?.sliceIndex ?: -1) + 1
        val newSlice = ChargeSliceEntity(
            sessionId = sessionId,
            sliceIndex = nextIndex,
            stationCounterStartKwh = stationCounterCurrentKwh,
            stationCounterLastKwh = stationCounterCurrentKwh,
            finalizedKwh = 0.0,
            startedAt = startTime
        )
        insertSlice(newSlice)
    }
}

