package com.convoscribe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recorded conversation. [audioPath] points at
 * `filesDir/recordings/<id>.wav`; the file is created by the
 * recording service and removed by [SessionRepository.deleteSession].
 *
 * [status] is the lifecycle marker (see [SessionStatus]); the UI
 * uses it to decide whether to show "Diarizing…" or a final speaker
 * list. [durationMs] is filled in when the recording stops; until
 * then it stays at 0.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val language: String,
    val audioPath: String = "",
    val status: SessionStatus
)
