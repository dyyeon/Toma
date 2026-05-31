package com.capstone.toma.storage

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.capstone.toma.model.RecipeSourceType
import org.json.JSONArray

@Entity(tableName = "stored_recipes")
data class StoredRecipeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val story: String,
    val time: Int,
    val difficulty: String,
    val servings: Int,
    val calories: Int,
    val rating: Double,
    val favorite: Boolean,
    val ingredients: List<String>,
    val steps: List<String>,
    val sourceType: RecipeSourceType,
    val timeText: String,
    val imageUri: String? = null,
    val updatedAt: Long
)

@Entity(tableName = "recent_recipe_history")
data class RecentRecipeHistoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val timeText: String,
    val sourceType: RecipeSourceType,
    val recipeDataJson: String?,
    val updatedAt: Long
)

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val lastUpdatedAt: Long
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: String,
    val imageUri: String?,
    val orderIndex: Int,
    val recipeContextJson: String? = null
)

class RecipeStorageConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return JSONArray(value).toString()
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val array = JSONArray(value)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }
    }

    @TypeConverter
    fun fromRecipeSourceType(value: RecipeSourceType): String = value.name

    @TypeConverter
    fun toRecipeSourceType(value: String): RecipeSourceType {
        return runCatching { RecipeSourceType.valueOf(value) }
            .getOrDefault(RecipeSourceType.TEXT)
    }
}

@Database(
    entities = [StoredRecipeEntity::class, RecentRecipeHistoryEntity::class, ChatSessionEntity::class, ChatMessageEntity::class],
    version = 8,
    exportSchema = false
)
@TypeConverters(RecipeStorageConverters::class)
abstract class RecipeStorageDatabase : RoomDatabase() {
    abstract fun recipeStorageDao(): RecipeStorageDao
    abstract fun chatStorageDao(): ChatStorageDao

    companion object {
        @Volatile
        private var instance: RecipeStorageDatabase? = null

        private val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stored_recipes ADD COLUMN imageUri TEXT")
            }
        }

        private val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recent_recipe_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        timeText TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        recipeDataJson TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val Migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Check if the entity is a table or a view before dropping
                db.query("SELECT type FROM sqlite_master WHERE name='recent_recipe_history'").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val type = cursor.getString(0)
                        if (type == "view") {
                            db.execSQL("DROP VIEW IF EXISTS recent_recipe_history")
                        } else {
                            db.execSQL("DROP TABLE IF EXISTS recent_recipe_history")
                        }
                    }
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recent_recipe_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        timeText TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        recipeDataJson TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // Corrects stored_recipes rows that were wrongly saved as IMAGE due to inferSourceType
        // treating any non-blank image_url as IMAGE. Camera-scanned recipes have content:// imageUri
        // and are left alone; web/YouTube recipes have http imageUri and are re-labelled.
        private val Migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastUpdatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        isUser INTEGER NOT NULL,
                        timestamp TEXT NOT NULL,
                        imageUri TEXT,
                        orderIndex INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val Migration7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN recipeContextJson TEXT")
            }
        }

        private val Migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE stored_recipes
                    SET sourceType = CASE
                        WHEN instr(lower(imageUri), 'youtube.com') > 0
                          OR instr(lower(imageUri), 'youtu.be') > 0 THEN 'YOUTUBE'
                        WHEN imageUri LIKE 'http%' THEN 'WEB'
                        ELSE sourceType
                    END
                    WHERE sourceType = 'IMAGE'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE recent_recipe_history
                    SET sourceType = (
                        SELECT sourceType FROM stored_recipes
                        WHERE stored_recipes.id = recent_recipe_history.id
                    )
                    WHERE sourceType = 'IMAGE'
                    AND EXISTS (
                        SELECT 1 FROM stored_recipes
                        WHERE stored_recipes.id = recent_recipe_history.id
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): RecipeStorageDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecipeStorageDatabase::class.java,
                    "recipe-storage.db"
                )
                    .addMigrations(Migration2To3)
                    .addMigrations(Migration3To4)
                    .addMigrations(Migration4To5)
                    .addMigrations(Migration5To6)
                    .addMigrations(Migration6To7)
                    .addMigrations(Migration7To8)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
