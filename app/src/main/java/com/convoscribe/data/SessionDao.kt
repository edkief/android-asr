package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [SessionEntity] rows. The reactive methods return
 * Flows so the UI re-renders the session list as the recorder creates
 * new sessions or diarization finishes. The other methods are
 * `suspend` so they can only be called from a coroutine.
 */
@Dao
interface SessionDao {
    @Insert
    suspend fun insert(entity: SessionEntity): Long

    @Update
    suspend fun update(entity: SessionEntity)

    /** All sessions, newest first. The home screen watches this. */
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** A single session, or `null` if the id does not exist. */
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    /** Synchronous getter; used by the recorder when it needs the row. */
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Sessions in a given status. Used by the diarization retry
     * loop to find sessions that ended in [SessionStatus.DIARIZATION_FAILED].
     */
    @Query("SELECT * FROM sessions WHERE status = :status")
    suspend fun findByStatus(status: SessionStatus): List<SessionEntity>

    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: SessionStatus)
}
