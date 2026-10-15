package com.convoscribe.data

import androidx.room.TypeConverter

/**
 * Room can't store enums directly; round-trip them through their
 * `name` so the database stays human-readable in `adb shell` dumps
 * and `sqlite3` queries. We never persist a `null` here because
 * every column that holds an enum is non-null on the entity side.
 */
class Converters {
    @TypeConverter
    fun fromSessionStatus(value: SessionStatus): String = value.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    @TypeConverter
    fun fromMessageKind(value: MessageKind): String = value.name

    @TypeConverter
    fun toMessageKind(value: String): MessageKind = MessageKind.valueOf(value)

    @TypeConverter
    fun fromMessageRole(value: MessageRole): String = value.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)
}
