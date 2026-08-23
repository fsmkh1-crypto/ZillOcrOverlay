package kr.co.zillocr.overlay.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TranslationEntity::class, GlossaryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao
    abstract fun glossaryDao(): GlossaryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translations_new` (
                        `sourceText` TEXT NOT NULL,
                        `translatedText` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        PRIMARY KEY(`sourceText`, `model`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `translations_new`
                    (`sourceText`, `translatedText`, `model`, `createdAt`, `lastUsedAt`, `useCount`)
                    SELECT `sourceText`, `translatedText`, `model`, `createdAt`, `lastUsedAt`, `useCount`
                    FROM `translations`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `translations`")
                db.execSQL("ALTER TABLE `translations_new` RENAME TO `translations`")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "zill_overlay.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
