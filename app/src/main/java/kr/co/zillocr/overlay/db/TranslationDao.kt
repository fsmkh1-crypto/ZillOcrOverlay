package kr.co.zillocr.overlay.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations WHERE sourceText = :sourceText LIMIT 1")
    fun find(sourceText: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: TranslationEntity)

    @Query("UPDATE translations SET lastUsedAt = :lastUsedAt, useCount = useCount + 1 WHERE sourceText = :sourceText")
    fun touch(sourceText: String, lastUsedAt: Long)

    @Query("SELECT * FROM translations ORDER BY lastUsedAt DESC LIMIT :limit")
    fun recent(limit: Int): List<TranslationEntity>

    @Query("SELECT COUNT(*) FROM translations")
    fun count(): Int

    @Query("DELETE FROM translations")
    fun clear()
}
