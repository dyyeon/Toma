package com.capstone.toma.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeStorageDao {
    @Query(
        """
        SELECT * FROM stored_recipes
        ORDER BY favorite DESC, updatedAt DESC, title ASC
        """
    )
    fun observeRecipes(): Flow<List<StoredRecipeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recipes: List<StoredRecipeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: StoredRecipeEntity)

    @Query("SELECT COUNT(*) FROM stored_recipes")
    suspend fun countRecipes(): Int

    @Query(
        """
        UPDATE stored_recipes
        SET favorite = :isFavorite, updatedAt = :updatedAt
        WHERE id = :recipeId
        """
    )
    suspend fun updateFavorite(recipeId: String, isFavorite: Boolean, updatedAt: Long)
    @Query("DELETE FROM stored_recipes WHERE id = :recipeId")
    suspend fun deleteById(recipeId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM stored_recipes WHERE id = :recipeId LIMIT 1)")
    fun isRecipeExists(recipeId: String): Flow<Boolean>
}
