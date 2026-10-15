package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [EnrolledSpeakerEntity] rows. The list is
 * expected to stay small (a handful of household voices), so the
 * whole table is loaded into memory when [getAll] runs.
 *
 * The unique index on `name` (see [EnrolledSpeakerEntity]) makes
 * [upsertByName] behave like an upsert: Room routes the insert
 * through `REPLACE`, so the same name always keeps a single row
 * with the newest embedding — older rows are gone in the same
 * transaction, no duplicates can accumulate.
 */
@Dao
interface EnrolledSpeakerDao {

    /**
     * Every enrolled speaker, alphabetical by name so the UI
     * list is stable.
     */
    @Query("SELECT * FROM enrolled_speakers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<EnrolledSpeakerEntity>>

    /**
     * Snapshot read used by the diarization pipeline: it needs
     * the embedding vectors up-front, not a flow.
     */
    @Query("SELECT * FROM enrolled_speakers ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<EnrolledSpeakerEntity>

    /**
     * Insert a new enrollment, or replace the row with the same
     * [EnrolledSpeakerEntity.name] (Room's `REPLACE` strategy
     * relies on the unique index on `name`). Saving the same
     * name twice keeps one row with the newer embedding.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertByName(entity: EnrolledSpeakerEntity): Long

    @Query("DELETE FROM enrolled_speakers WHERE id = :id")
    suspend fun delete(id: Long)
}
