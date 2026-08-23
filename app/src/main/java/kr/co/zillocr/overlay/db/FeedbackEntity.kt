package kr.co.zillocr.overlay.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_feedback")
data class FeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceText: String,
    val model: String,
    val rating: Int,
    val category: String?,
    val correctedText: String?,
    val speakerSource: String?,
    val createdAt: Long
)
