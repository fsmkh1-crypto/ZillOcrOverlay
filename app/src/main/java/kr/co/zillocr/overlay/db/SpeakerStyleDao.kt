package kr.co.zillocr.overlay.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpeakerStyleDao {
    @Query("SELECT * FROM speaker_styles WHERE sourceName = :sourceName LIMIT 1")
    fun find(sourceName: String): SpeakerStyleEntity?

    @Query("SELECT * FROM speaker_styles ORDER BY sourceName COLLATE NOCASE ASC")
    fun all(): List<SpeakerStyleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SpeakerStyleEntity)

    @Query("DELETE FROM speaker_styles WHERE sourceName = :sourceName")
    fun delete(sourceName: String)
}
