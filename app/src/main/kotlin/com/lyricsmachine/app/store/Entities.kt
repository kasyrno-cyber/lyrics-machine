package com.lyricsmachine.app.store

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.lyricsmachine.core.Lang

@Entity
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val language: Lang,
    val lyricsText: String,
    val audioUri: String?,
    val createdAt: Long,
)

/** One saved timing. [wordIndex] == -1 means the whole line. */
@Entity(primaryKeys = ["songId", "lineIndex", "wordIndex"])
data class TimingEntity(
    val songId: Long,
    val lineIndex: Int,
    val wordIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val matched: Boolean,
)

@Entity
data class AnalysisEntity(
    @PrimaryKey val songId: Long,
    val matchRatio: Double,
    val modelName: String,
    val analysedAt: Long,
)

enum class SongMode { AUDIO_READY, AUDIO_NOT_ANALYSED, LYRICS_ONLY }

data class SongSummary(val id: Long, val title: String, val artist: String, val mode: SongMode)

class Converters {
    @TypeConverter fun langToString(lang: Lang): String = lang.name
    @TypeConverter fun stringToLang(value: String): Lang = Lang.valueOf(value)
}
