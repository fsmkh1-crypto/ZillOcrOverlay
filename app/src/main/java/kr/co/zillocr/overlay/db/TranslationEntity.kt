package kr.co.zillocr.overlay.db

import androidx.room.Entity

@Entity(
    tableName = "translations",
    primaryKeys = ["sourceText", "model"]
)
data class TranslationEntity(
    val sourceText: String,
    val translatedText: String,
    val model: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val useCount: Int
)
