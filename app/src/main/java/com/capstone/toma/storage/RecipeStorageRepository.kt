package com.capstone.toma.storage

import android.content.Context
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.model.StoredRecipe
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecipeStorageRepository private constructor(
    private val dao: RecipeStorageDao
) {
    fun observeRecipes(): Flow<List<StoredRecipe>> {
        return dao.observeRecipes().map { recipes ->
            recipes.map(StoredRecipeEntity::toModel)
        }
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

private fun seedRecipes(): List<StoredRecipe> = listOf(
    StoredRecipe(
        id = "kimchi",
        title = "김치볶음밥",
        category = "한식",
        story = "잘 익은 김치와 참기름으로 빠르게 완성하는 집밥 메뉴입니다.",
        time = 18,
        difficulty = "쉬움",
        servings = 2,
        calories = 520,
        rating = 4.8,
        favorite = true,
        ingredients = listOf("밥 2공기", "김치 1컵", "대파 1/2대", "고추장 1큰술"),
        steps = listOf(
            "김치와 대파를 먼저 볶아 향을 올립니다.",
            "밥과 양념을 넣고 고르게 섞어 볶습니다.",
            "계란과 깨를 올려 마무리합니다."
        ),
        sourceType = RecipeSourceType.YOUTUBE,
        timeText = "2시간 전 저장"
    ),
    StoredRecipe(
        id = "pasta",
        title = "바질 크림 파스타",
        category = "파스타",
        story = "부드러운 크림과 바질 향이 어우러진 한 그릇 파스타입니다.",
        time = 25,
        difficulty = "보통",
        servings = 2,
        calories = 670,
        rating = 4.6,
        favorite = false,
        ingredients = listOf("스파게티면 160g", "생크림 180ml", "바질 페스토 2큰술", "마늘 3쪽"),
        steps = listOf(
            "면을 알단테로 삶습니다.",
            "마늘과 크림, 바질 페스토로 소스를 만듭니다.",
            "면과 면수를 넣고 걸쭉하게 마무리합니다."
        ),
        sourceType = RecipeSourceType.TEXT,
        timeText = "어제 저장"
    ),
    StoredRecipe(
        id = "latte",
        title = "말차 라떼",
        category = "음료",
        story = "진한 말차와 우유의 균형이 좋은 홈카페 메뉴입니다.",
        time = 7,
        difficulty = "쉬움",
        servings = 1,
        calories = 180,
        rating = 4.7,
        favorite = true,
        ingredients = listOf("말차 가루 2작은술", "우유 220ml", "꿀 1작은술", "얼음 적당량"),
        steps = listOf(
            "말차를 따뜻한 물에 먼저 풉니다.",
            "우유와 꿀을 섞어 컵에 담습니다.",
            "얼음 위로 말차를 부어 층을 만듭니다."
        ),
        sourceType = RecipeSourceType.IMAGE,
        timeText = "3일 전 저장"
    ),
    StoredRecipe(
        id = "toast",
        title = "버섯 브런치 토스트",
        category = "브런치",
        story = "버터에 볶은 버섯과 사워도우가 잘 어울리는 브런치입니다.",
        time = 15,
        difficulty = "보통",
        servings = 2,
        calories = 410,
        rating = 4.9,
        favorite = true,
        ingredients = listOf("사워도우 2장", "버섯 믹스 150g", "버터 1큰술", "수란 2개"),
        steps = listOf(
            "빵을 노릇하게 굽습니다.",
            "버섯을 버터에 볶아 수분을 날립니다.",
            "빵 위에 버섯과 수란을 올려 마무리합니다."
        ),
        sourceType = RecipeSourceType.TEXT,
        timeText = "지난주 저장"
    )
)
