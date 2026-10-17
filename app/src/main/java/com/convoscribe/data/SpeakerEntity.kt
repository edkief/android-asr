package com.convoscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A speaker discovered during diarization, identified by its
 * 0-based [speakerIndex] within a session. The default [label] is
 * `"Speaker ${index + 1}"`; the user can rename a speaker by
 * updating the row. The diarization update wipes all rows for a
 * session and re-inserts them; custom names are carried over to
 * the matching new speakers by
 * [com.convoscribe.diarization.SpeakerLabelCarryOver].
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

/**
 * Label a diarized speaker gets until the user renames it:
 * `"Speaker 1"` for index 0, and so on.
 */
fun defaultSpeakerLabel(speakerIndex: Int): String = "Speaker ${speakerIndex + 1}"
