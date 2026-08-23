package kr.co.zillocr.overlay.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speakers")
data class SpeakerEntity(
    @PrimaryKey val sourceName: String,
    val targetName: String,
    val updatedAt: Long
)
