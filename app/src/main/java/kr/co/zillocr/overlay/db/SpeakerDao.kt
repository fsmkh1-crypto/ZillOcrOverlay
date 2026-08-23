package kr.co.zillocr.overlay.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speakers ORDER BY sourceName COLLATE NOCASE ASC")
    fun all(): List<SpeakerEntity>

    @Query("SELECT * FROM speakers WHERE sourceName = :sourceName LIMIT 1")
    fun find(sourceName: String): SpeakerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SpeakerEntity)

    @Query("DELETE FROM speakers WHERE sourceName = :sourceName")
    fun delete(sourceName: String)

    @Query("SELECT COUNT(*) FROM speakers")
    fun count(): Int
}
