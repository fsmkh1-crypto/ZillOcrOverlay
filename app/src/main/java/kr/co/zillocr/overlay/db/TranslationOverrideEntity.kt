package kr.co.zillocr.overlay.db

import androidx.room.Entity

@Entity(
    tableName = "translation_overrides",
    primaryKeys = ["sourceText", "model"]
)
data class TranslationOverrideEntity(
    val sourceText: String,
    val model: String,
    val correctedText: String,
    val speakerSource: String?,
    val updatedAt: Long
)
