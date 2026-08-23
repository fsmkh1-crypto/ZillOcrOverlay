package kr.co.zillocr.overlay.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FeedbackDao {
    @Insert
    fun insert(entity: FeedbackEntity): Long

    @Query("SELECT * FROM translation_feedback WHERE speakerSource = :speakerSource AND correctedText IS NOT NULL ORDER BY createdAt DESC LIMIT :limit")
    fun correctedForSpeaker(speakerSource: String, limit: Int): List<FeedbackEntity>

    @Query("SELECT * FROM translation_feedback ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): List<FeedbackEntity>
}
