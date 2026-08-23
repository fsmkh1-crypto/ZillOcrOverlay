package kr.co.zillocr.overlay.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationOverrideDao {
    @Query("SELECT * FROM translation_overrides WHERE sourceText = :sourceText AND model = :model LIMIT 1")
    fun find(sourceText: String, model: String): TranslationOverrideEntity?

    @Query("SELECT * FROM translation_overrides WHERE speakerSource = :speakerSource ORDER BY updatedAt DESC LIMIT :limit")
    fun recentForSpeaker(speakerSource: String, limit: Int): List<TranslationOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: TranslationOverrideEntity)

    @Query("DELETE FROM translation_overrides WHERE sourceText = :sourceText AND model = :model")
    fun delete(sourceText: String, model: String)
}
