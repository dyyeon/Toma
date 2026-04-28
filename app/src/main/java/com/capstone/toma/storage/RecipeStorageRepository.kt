package com.capstone.toma.storage

import android.content.Context
import com.capstone.toma.model.RecentRecipeRecord
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.model.StoredRecipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class RecipeStorageRepository private constructor(
    private val dao: RecipeStorageDao
) {
    fun observeRecipes(): Flow<List<StoredRecipe>> {
        return dao.observeRecipes()
            .map { recipes -> recipes.map(StoredRecipeEntity::toModel) }
            .catch { emit(emptyList()) }
    }

    fun observeRecentRecipes(limit: Int): Flow<List<RecentRecipeRecord>> {
        return dao.observeRecentRecipes(limit)
            .map { recipes -> recipes.map(RecentRecipeHistoryEntity::toModel) }
            .catch { emit(emptyList()) }
    }

    suspend fun ensureSeeded() {
        if (dao.countRecipes() > 0) return
        dao.insertAll(seedRecipes().map(StoredRecipe::toEntity))
    }

    suspend fun updateFavorite(recipeId: String, isFavorite: Boolean) {
        dao.updateFavorite(
            recipeId = recipeId,
            isFavorite = isFavorite,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun saveRecipe(recipe: StoredRecipe) {
        dao.upsert(recipe.toEntity())
    }

    suspend fun saveRecentRecipe(record: RecentRecipeRecord) {
        dao.upsertRecentRecipe(record.toEntity())
        dao.trimRecentRecipes(keepCount = 5)
    }

    suspend fun deleteRecipe(recipeId: String) {
        dao.deleteById(recipeId)
    }

    fun isRecipeSaved(recipeId: String): Flow<Boolean> {
        return dao.isRecipeExists(recipeId)
    }

    companion object {
        @Volatile
        private var instance: RecipeStorageRepository? = null

        fun getInstance(context: Context): RecipeStorageRepository {
            return instance ?: synchronized(this) {
                instance ?: RecipeStorageRepository(
                    RecipeStorageDatabase.getInstance(context).recipeStorageDao()
                ).also { instance = it }
            }
        }
    }
}

private fun StoredRecipeEntity.toModel(): StoredRecipe = StoredRecipe(
    id = id,
    title = title,
    category = category,
    story = story,
    time = time,
    difficulty = difficulty,
    servings = servings,
    calories = calories,
    rating = rating,
    favorite = favorite,
    ingredients = ingredients,
    steps = steps,
    sourceType = sourceType,
    timeText = timeText,
    imageUri = imageUri
)

private fun StoredRecipe.toEntity(): StoredRecipeEntity = StoredRecipeEntity(
    id = id,
    title = title,
    category = category,
    story = story,
    time = time,
    difficulty = difficulty,
    servings = servings,
    calories = calories,
    rating = rating,
    favorite = favorite,
    ingredients = ingredients,
    steps = steps,
    sourceType = sourceType,
    timeText = timeText,
    imageUri = imageUri,
    updatedAt = System.currentTimeMillis()
)

private fun RecentRecipeHistoryEntity.toModel(): RecentRecipeRecord = RecentRecipeRecord(
    id = id,
    title = title,
    timeText = timeText,
    sourceType = sourceType,
    recipeDataJson = recipeDataJson
)

private fun RecentRecipeRecord.toEntity(): RecentRecipeHistoryEntity = RecentRecipeHistoryEntity(
    id = id,
    title = title,
    timeText = timeText,
    sourceType = sourceType,
    recipeDataJson = recipeDataJson,
    updatedAt = System.currentTimeMillis()
)

private fun seedRecipes(): List<StoredRecipe> = listOf(
    StoredRecipe(
        id = "kimchi_fried_rice",
        title = "\uAE40\uCE58\uBCF6\uC74C\uBC25",
        category = "\uD55C\uC2DD",
        story = "\uC2E0\uAE40\uCE58\uB85C \uBE60\uB974\uAC8C \uB9CC\uB4DC\uB294 \uB4E0\uB4E0\uD55C \uD55C \uB07C \uBA54\uB274\uC785\uB2C8\uB2E4.",
        time = 18,
        difficulty = "\uC26C\uC6C0",
        servings = 2,
        calories = 520,
        rating = 4.8,
        favorite = true,
        ingredients = listOf(
            "\uBC25 2\uACF5\uAE30",
            "\uAE40\uCE58 1\uCEF5",
            "\uB300\uD30C 1/2\uB300",
            "\uACE0\uCD94\uC7A5 1\uC2A4\uD47C"
        ),
        steps = listOf(
            "\uAE40\uCE58\uC640 \uB300\uD30C\uB97C \uBA3C\uC800 \uBCF6\uC544 \uD5A5\uC744 \uB0C5\uB2C8\uB2E4.",
            "\uBC25\uACFC \uC591\uB150\uC744 \uB123\uACE0 \uACE0\uB8E8 \uBCF6\uC2B5\uB2C8\uB2E4.",
            "\uACC4\uB780 \uD6C4\uB77C\uC774\uB97C \uC62C\uB824 \uB9C8\uBB34\uB9AC\uD569\uB2C8\uB2E4."
        ),
        sourceType = RecipeSourceType.YOUTUBE,
        timeText = "2\uC2DC\uAC04 \uC804"
    ),
    StoredRecipe(
        id = "cream_pasta",
        title = "\uD06C\uB9BC \uD30C\uC2A4\uD0C0",
        category = "\uC591\uC2DD",
        story = "\uBD80\uB4DC\uB7EC\uC6B4 \uC18C\uC2A4\uAC00 \uB3CB\uBCF4\uC774\uB294 \uAC00\uBCBC\uC6B4 \uD30C\uC2A4\uD0C0\uC785\uB2C8\uB2E4.",
        time = 25,
        difficulty = "\uBCF4\uD1B5",
        servings = 2,
        calories = 670,
        rating = 4.6,
        favorite = false,
        ingredients = listOf(
            "\uC2A4\uD30C\uAC8C\uD2F0\uBA74 160g",
            "\uC0DD\uD06C\uB9BC 180ml",
            "\uB9C8\uB298 3\uCABD",
            "\uD30C\uB9C8\uC0B0 \uCE58\uC988"
        ),
        steps = listOf(
            "\uBA74\uC744 \uC54C\uB371\uD14C\uB85C \uC0B6\uC2B5\uB2C8\uB2E4.",
            "\uB9C8\uB298\uACFC \uC0DD\uD06C\uB9BC\uC73C\uB85C \uC18C\uC2A4\uB97C \uB9CC\uB4ED\uB2C8\uB2E4.",
            "\uBA74\uACFC \uC18C\uC2A4\uB97C \uD569\uCCD0 \uAC00\uBCBC\uAC8C \uC871\uC5EC \uB9C8\uBB34\uB9AC\uD569\uB2C8\uB2E4."
        ),
        sourceType = RecipeSourceType.TEXT,
        timeText = "\uC5B4\uC81C \uC800\uC7A5"
    ),
    StoredRecipe(
        id = "matcha_latte",
        title = "\uB9D0\uCC28 \uB77C\uB5BC",
        category = "\uC74C\uB8CC",
        story = "\uC9C4\uD55C \uB9D0\uCC28\uC640 \uC6B0\uC720\uAC00 \uC5B4\uC6B0\uB7EC\uC9C4 \uBD80\uB4DC\uB7EC\uC6B4 \uC74C\uB8CC\uC785\uB2C8\uB2E4.",
        time = 7,
        difficulty = "\uC26C\uC6C0",
        servings = 1,
        calories = 180,
        rating = 4.7,
        favorite = true,
        ingredients = listOf(
            "\uB9D0\uCC28 \uAC00\uB8E8 2\uC2A4\uD47C",
            "\uC6B0\uC720 220ml",
            "\uAFC0 1\uC2A4\uD47C",
            "\uC5BC\uC74C \uC57D\uAC04"
        ),
        steps = listOf(
            "\uB9D0\uCC28 \uAC00\uB8E8\uB97C \uBB3C\uC5D0 \uAC1C\uC5B4 \uB179\uC785\uB2C8\uB2E4.",
            "\uC6B0\uC720\uC640 \uAFC0\uC744 \uC11E\uC5B4 \uCEF5\uC5D0 \uB2F4\uC2B5\uB2C8\uB2E4.",
            "\uB9D0\uCC28\uB97C \uBD80\uC5B4 \uC5C4\uC11C\uB97C \uB9C8\uBB34\uB9AC\uD569\uB2C8\uB2E4."
        ),
        sourceType = RecipeSourceType.IMAGE,
        timeText = "3\uC77C \uC804"
    ),
    StoredRecipe(
        id = "mushroom_toast",
        title = "\uBC84\uC12F \uD1A0\uC2A4\uD2B8",
        category = "\uBE0C\uB7F0\uCE58",
        story = "\uACE0\uC18C\uD55C \uBC84\uD130 \uD5A5\uACFC \uBC84\uC12F\uC774 \uC798 \uC5B4\uC6B8\uB9AC\uB294 \uBE0C\uB7F0\uCE58 \uBA54\uB274\uC785\uB2C8\uB2E4.",
        time = 15,
        difficulty = "\uBCF4\uD1B5",
        servings = 2,
        calories = 410,
        rating = 4.9,
        favorite = true,
        ingredients = listOf(
            "\uC0AC\uC6B0\uB3C4\uC6B0 2\uC7A5",
            "\uBC84\uC12F \uBAA8\uB4EC 150g",
            "\uBC84\uD130 1\uC2A4\uD47C",
            "\uACC4\uB780 2\uAC1C"
        ),
        steps = listOf(
            "\uBE75\uC744 \uB178\uB987\uD558\uAC8C \uAD7D\uC2B5\uB2C8\uB2E4.",
            "\uBC84\uC12F\uC744 \uBC84\uD130\uC5D0 \uBCF6\uC544 \uC218\uBD84\uC744 \uC904\uC785\uB2C8\uB2E4.",
            "\uBE75 \uC704\uC5D0 \uBC84\uC12F\uACFC \uACC4\uB780\uC744 \uC62C\uB824 \uB9C8\uBB34\uB9AC\uD569\uB2C8\uB2E4."
        ),
        sourceType = RecipeSourceType.TEXT,
        timeText = "\uC9C0\uB09C\uC8FC \uC800\uC7A5"
    )
)
