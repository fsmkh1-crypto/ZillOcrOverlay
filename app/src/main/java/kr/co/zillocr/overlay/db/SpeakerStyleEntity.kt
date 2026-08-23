package kr.co.zillocr.overlay.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speaker_styles")
data class SpeakerStyleEntity(
    @PrimaryKey val sourceName: String,
    val styleNote: String,
    val updatedAt: Long
)
