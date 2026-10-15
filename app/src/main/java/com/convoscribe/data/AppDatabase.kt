package com.convoscribe.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database holding the five entity families that survive an
 * app restart: sessions, transcript segments, speaker labels,
 * LLM chat messages, and (v2) enrolled speakers. The current
 * version is 2; the generated JSON for both versions is committed
 * under `app/schemas/` so the auto-migration from 1 to 2 can be
 * diffed against it.
 *
 * The 1 → 2 migration adds the `enrolled_speakers` table only —
 * no existing table is touched — and Room generates the matching
 * `CREATE TABLE` SQL at first launch after the upgrade. The
 * migration is automatic because the diff is purely additive;
 * later migrations that change a column will need a hand-written
 * spec.
 *
 * [Converters] is registered so the enum columns ([SessionStatus],
 * [MessageKind], [MessageRole]) round-trip as their `name`.
 */
@Database(
    entities = [
        SessionEntity::class,
        SegmentEntity::class,
        SpeakerEntity::class,
        ChatMessageEntity::class,
        EnrolledSpeakerEntity::class
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2)
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun segmentDao(): SegmentDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun enrolledSpeakerDao(): EnrolledSpeakerDao

    companion object {
        const val NAME: String = "convoscribe.db"
    }
}
