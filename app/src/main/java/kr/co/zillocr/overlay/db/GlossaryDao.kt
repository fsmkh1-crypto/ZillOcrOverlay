package kr.co.zillocr.overlay.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary ORDER BY sourceTerm COLLATE NOCASE ASC")
    fun all(): List<GlossaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: GlossaryEntity)

    @Query("DELETE FROM glossary WHERE sourceTerm = :sourceTerm")
    fun delete(sourceTerm: String)
}
