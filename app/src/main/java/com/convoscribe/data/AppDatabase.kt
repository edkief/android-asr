package com.convoscribe.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database holding the four entity families that survive an
 * app restart: sessions, transcript segments, speaker labels and
 * LLM chat messages. Version is 1: this is the first schema, and
 * the generated JSON is committed under `app/schemas/` so future
 * migrations can be diffed against it.
 *
 * [Converters] is registered so the enum columns ([SessionStatus],
 * [MessageKind], [MessageRole]) round-trip as their `name`.
 */
@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        SpeakerEntity::class,
        ChatMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun segmentDao(): SegmentDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        const val NAME: String = "convoscribe.db"
    }
}
