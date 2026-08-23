package kr.co.zillocr.overlay.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translations")
data class TranslationEntity(
    @PrimaryKey val sourceText: String,
    val translatedText: String,
    val model: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val useCount: Int
)
