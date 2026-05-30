package com.capstone.toma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PublicRecipe(
    val name: String,
    val category: String,
    val calories: String,
    val mainImageUrl: String,
    val ingredients: List<String>,
    val nutritionInfo: Map<String, String>,
    val steps: List<String>
) {
    fun toRecipeJson(): String {
        val ingredientsArray = org.json.JSONArray().apply {
            ingredients.forEach { put(it) }
        }
        val stepsArray = org.json.JSONArray().apply {
            steps.forEach { put(it) }
        }
        val recipeData = JSONObject().apply {
            put("title", name)
            put("category", category)
            put("ingredients", ingredientsArray)
            put("steps", stepsArray)
            put("difficulty", "보통")
            put("time", "")
            put("calories", calories)
            put("image_url", mainImageUrl)
        }
        return JSONObject().apply {
            put("type", "recipe_search")
            put("keyword", name)
            put("response", "${name} 레시피를 찾았어요!")
            put("recipe_data", recipeData)
        }.toString()
    }
}

class PublicRecipeManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val apiKey = BuildConfig.FOOD_SAFETY_API_KEY
    private val baseUrl = "https://openapi.foodsafetykorea.go.kr/api/$apiKey/COOKRCP01/json/1/3"

    suspend fun searchRecipe(keyword: String): PublicRecipe? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/RCP_NM=$keyword"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                parseRecipe(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRecipe(json: String): PublicRecipe? {
        return try {
            val root = JSONObject(json)
            val cookRcp = root.optJSONObject("COOKRCP01") ?: return null
            val total = cookRcp.optInt("total_count", 0)
            if (total == 0) return null
            val rows = cookRcp.optJSONArray("row") ?: return null
            if (rows.length() == 0) return null

            val item = rows.getJSONObject(0)

            val name = item.optString("RCP_NM", "")
            val category = item.optString("RCP_PAT2", "기타")
            val calories = item.optString("INFO_ENG", "")
            val mainImageUrl = item.optString("ATT_FILE_NO_MAIN", "").replace("http://", "https://")

            val rawIngredients = item.optString("RCP_PARTS_DTLS", "")
            val ingredients = rawIngredients
                .split(",", "·")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val nutritionInfo = mapOf(
                "탄수화물" to item.optString("INFO_CAR", ""),
                "단백질" to item.optString("INFO_PRO", ""),
                "지방" to item.optString("INFO_FAT", ""),
                "나트륨" to item.optString("INFO_NA", ""),
                "칼로리" to calories
            ).filterValues { it.isNotBlank() }

            val steps = (1..20).mapNotNull { i ->
                val step = item.optString("MANUAL%02d".format(i), "").trim()
                step.ifEmpty { null }
            }

            PublicRecipe(
                name = name,
                category = category,
                calories = calories,
                mainImageUrl = mainImageUrl,
                ingredients = ingredients,
                nutritionInfo = nutritionInfo,
                steps = steps
            )
        } catch (e: Exception) {
            null
        }
    }
}
