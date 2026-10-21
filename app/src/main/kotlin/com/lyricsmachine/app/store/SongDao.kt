package com.lyricsmachine.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM SongEntity ORDER BY title COLLATE NOCASE ASC, id ASC")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT songId FROM TimingEntity")
    fun observeTimedSongIds(): Flow<List<Long>>

    @Query("SELECT * FROM SongEntity WHERE id = :id")
    suspend fun get(id: Long): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity): Long

    @Query("DELETE FROM SongEntity WHERE id = :id")
    suspend fun deleteSong(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimings(timings: List<TimingEntity>)

    @Query("DELETE FROM TimingEntity WHERE songId = :songId")
    suspend fun deleteTimings(songId: Long)

    @Query("SELECT * FROM TimingEntity WHERE songId = :songId ORDER BY lineIndex, wordIndex")
    suspend fun timings(songId: Long): List<TimingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysis(analysis: AnalysisEntity)

    @Query("DELETE FROM AnalysisEntity WHERE songId = :songId")
    suspend fun deleteAnalysis(songId: Long)

    @Query("SELECT * FROM AnalysisEntity WHERE songId = :songId")
    suspend fun analysis(songId: Long): AnalysisEntity?
}
