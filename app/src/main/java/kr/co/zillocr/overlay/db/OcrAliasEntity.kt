package kr.co.zillocr.overlay.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ocr_aliases")
data class OcrAliasEntity(
    @PrimaryKey val observedText: String,
    val canonicalText: String,
    val updatedAt: Long
)
