package com.convoscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A speaker discovered during diarization, identified by its
 * 0-based [speakerIndex] within a session. The default [label] is
 * `"Speaker ${index + 1}"`; the user can rename a speaker by
 * updating the row (the diarization update wipes all rows for a
 * session and re-inserts them, so renames are reset by a re-run).
 *
 * The composite primary key `(sessionId, speakerIndex)` matches
 * the way the diarizer emits its assignments.
 */
@Entity(
    tableName = "speakers",
    primaryKeys = ["sessionId", "speakerIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SpeakerEntity(
    val sessionId: Long,
    val speakerIndex: Int,
    val label: String
)
