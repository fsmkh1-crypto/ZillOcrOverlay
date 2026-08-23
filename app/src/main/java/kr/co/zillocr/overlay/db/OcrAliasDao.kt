package kr.co.zillocr.overlay.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OcrAliasDao {
    @Query("SELECT * FROM ocr_aliases WHERE observedText = :observedText LIMIT 1")
    fun find(observedText: String): OcrAliasEntity?

    @Query("SELECT * FROM ocr_aliases ORDER BY updatedAt DESC")
    fun all(): List<OcrAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: OcrAliasEntity)

    @Query("DELETE FROM ocr_aliases WHERE observedText = :observedText")
    fun delete(observedText: String)
}
