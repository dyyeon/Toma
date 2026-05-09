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
    entities = [StoredRecipeEntity::class, RecentRecipeHistoryEntity::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(RecipeStorageConverters::class)
abstract class RecipeStorageDatabase : RoomDatabase() {
    abstract fun recipeStorageDao(): RecipeStorageDao

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
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
