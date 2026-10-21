package com.lyricsmachine.app.store

import androidx.room.withTransaction
import com.lyricsmachine.core.align.Alignment
import com.lyricsmachine.core.align.LineTiming
import com.lyricsmachine.core.align.WordTiming
import com.lyricsmachine.core.lyrics.LyricsParser
import com.lyricsmachine.core.playback.LrcTimings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine

class SongRepository(
    private val db: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uriReadable: (String) -> Boolean,
) {
    private val dao = db.songDao()

    /** Re-evaluates [uriReadable] on every emission; the transform runs on [ioDispatcher], not the collector's. */
    fun observeLibrary(): Flow<List<SongSummary>> =
        combine(dao.observeSongs(), dao.observeTimedSongIds()) { songs, timed ->
            val timedSet = timed.toSet()
            songs.map { s ->
                val uri = s.audioUri
                val mode = when {
                    uri == null || !uriReadable(uri) -> SongMode.LYRICS_ONLY
                    s.id in timedSet || hasLrcTimings(s) -> SongMode.AUDIO_READY
                    else -> SongMode.AUDIO_NOT_ANALYSED
                }
                SongSummary(s.id, s.title, s.artist, mode)
            }
        }.flowOn(ioDispatcher)

    private fun hasLrcTimings(song: SongEntity): Boolean =
        LrcTimings.fromLyrics(LyricsParser.parse(song.lyricsText)).isNotEmpty()

    suspend fun get(id: Long): SongEntity? = dao.get(id)

    /** Inserts or updates; clears timings and analysis if lyrics or audio changed. */
    suspend fun upsert(song: SongEntity): Long = db.withTransaction {
        val existing = if (song.id != 0L) dao.get(song.id) else null
        val id = dao.insertSong(song)
        if (existing != null && (existing.lyricsText != song.lyricsText || existing.audioUri != song.audioUri)) {
            dao.deleteAnalysis(id)
        }
        id
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.deleteAnalysis(id)
        dao.deleteSong(id)
    }

    /** Replaces existing results; with no lines, only the analysis row is stored. */
    suspend fun saveAlignment(songId: Long, alignment: Alignment, modelName: String) = db.withTransaction {
        dao.deleteTimings(songId)
        dao.deleteAnalysis(songId)
        dao.insertAnalysis(AnalysisEntity(songId, alignment.matchRatio, modelName, System.currentTimeMillis()))
        if (alignment.lines.isNotEmpty()) {
            val rows = alignment.lines.map { TimingEntity(songId, it.lineIndex, -1, it.startMs, it.endMs, true) } +
                alignment.words.map { TimingEntity(songId, it.lineIndex, it.wordIndex, it.startMs, it.endMs, it.matched) }
            dao.insertTimings(rows)
        }
    }

    /** Saved line/word timings if any; otherwise line timings from LRC tags (no word timings). */
    suspend fun loadTimings(songId: Long): Pair<List<LineTiming>, List<WordTiming>> {
        val rows = dao.timings(songId)
        val lines = rows.filter { it.wordIndex == -1 }.map { LineTiming(it.lineIndex, it.startMs, it.endMs) }
        if (lines.isEmpty()) {
            val song = dao.get(songId) ?: return emptyList<LineTiming>() to emptyList()
            return LrcTimings.fromLyrics(LyricsParser.parse(song.lyricsText)) to emptyList()
        }
        val words = rows.filter { it.wordIndex >= 0 }
            .map { WordTiming(it.lineIndex, it.wordIndex, it.startMs, it.endMs, it.matched) }
        return lines to words
    }

    suspend fun analysis(songId: Long): AnalysisEntity? = dao.analysis(songId)
}
