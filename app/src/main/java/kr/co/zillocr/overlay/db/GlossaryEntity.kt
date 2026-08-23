package kr.co.zillocr.overlay.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "glossary")
data class GlossaryEntity(
    @PrimaryKey val sourceTerm: String,
    val targetTerm: String,
    val updatedAt: Long
)
