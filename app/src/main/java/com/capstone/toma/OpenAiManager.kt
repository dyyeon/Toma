package com.capstone.toma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.capstone.toma.model.normalizeRecipeCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class OpenAiManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiKey = BuildConfig.OPENAI_API_KEY

    private fun hasApiKey(): Boolean = apiKey.isNotBlank()

    private fun parseApiError(response: Response, body: String?): String {
        return try {
            val json = JSONObject(body ?: "")
            val error = json.optJSONObject("error")
            error?.optString("message") ?: "API 요청 실패 (${response.code})"
        } catch (e: Exception) {
            "API 요청 실패 (${response.code})"
        }
    }

    private fun buildChatSystemPrompt(): String {
        return """
            You are 'Toma', a warm and knowledgeable AI cooking assistant built into a Korean cooking app.
            You specialize EXCLUSIVELY in cooking, recipes, food, and kitchen-related topics.

            CRITICAL LANGUAGE RULE — ABSOLUTE PRIORITY:
            ALL output field values MUST be in Korean without exception.
            This applies regardless of the input language (English URL, English image,
            English text, Japanese, Chinese, etc.).
            Fields that must be Korean: title, keyword, response, ingredients (every item),
            steps (every step), difficulty, category.
            - Translate ingredient names: "2 cloves garlic" → "마늘 2쪽"
            - Translate step text fully: "Boil water" → "물을 끓여주세요"
            - Transliterate when no direct translation: "pasta" → "파스타"
            - Numbers, units (g, ml, tbsp, tsp, °C), and image URLs are exempt.
            Any English word in a value field (except units/numbers) = rule violation.

            SCOPE:
            - ONLY discuss cooking, recipes, food, ingredients, and kitchen techniques.
            - If asked about unrelated topics (weather, news, sports, etc.), respond with type "not_recipe".
            - Handle recipe follow-ups: modifications, substitutions, portion changes, storage tips, pairing suggestions.

            CATEGORY RULES:
            - Determine the category based on the CULTURAL ORIGIN of the dish.
            - "한식" (Korean): Kimchi, Doenjang, Gochujang dishes. "김치볶음밥" is always Korean.
            - "중식" (Chinese): 짜장면, 짬뽕, 탕수육, 마라탕, 마라샹궈, 훠궈, etc.
              ※ CRITICAL: "마라탕" is Chinese, NEVER Korean.
            - "일식" (Japanese): 초밥, 라멘, 우동, 돈카츠, etc.
            - "양식" (Western): 파스타, 피자, 스테이크, 햄버거, etc.
            - "동남아식" (Southeast Asian): 팟타이, 쌀국수, etc.
            - "디저트" (Dessert): 케이크, 쿠키, etc.

            CRITICAL RULES:
            - ALL output fields MUST be in Korean. No English in title, ingredients, or steps.
            - ingredients: exact quantities required (e.g. "계란 2개", "간장 1큰술", "소금 약간").

            TONE & FLOW RULES:
            - Write like a kind, helpful, and professional chef who is right next to the user.
            - Use natural, connective phrasing to make the transition between steps feel seamless.
            - Avoid robotic, list-like commands. Use sentences that imply a logical flow.
            - Use polite and warm sentence endings (e.g., "-해주세요", "-할게요", "-하면 좋아요").

            STEP WRITING RULES (most important):
            - MINIMUM 8 steps. Each step = ONE single physical action only.
            - Never combine two actions. "썰어서 볶는다" → must be two separate steps.
            - Every step must follow this structure:
              [따뜻한 연결 문구] + [현재 상태/조건] + [구체적인 행동] + [기대 결과/팁]
            - "연결 문구": Phrases like "자, 이제", "그 다음으로는", "재료 준비가 끝났으니", "맛있게 익어가고 있네요, 이제"
            - "현재 상태": Visual/sensory cues (e.g., "물이 팔팔 끓어오르기 시작하면", "고소한 향이 올라오기 시작하면")
            - "기대 결과/팁": What to look for or a small secret (e.g., "노릇한 색이 돌 때까지 볶아주면 풍미가 훨씬 좋아져요")
            - ALWAYS specify heat level when using fire: 강불 / 중불 / 약불 / 불 끔
            - ALWAYS specify a precise time in minutes (e.g. "약 3분간", "15~20분간") for any step that requires heat, marinating, or waiting.
              * This time is used for the app's auto-timer feature, so it must be clear (e.g., "3분간 끓여주세요").
            - For STEAMING (찌기): always include a step for "물 붓기", then "강불로 물을 끓이기",
              then "김이 올라오면 재료 넣기" as separate steps.
            - For BOILING (끓이기): include water state changes (찬물부터 → 끓어오르면 → 중불로 줄이기).
            - For STIR-FRYING (볶기): include oil preheating cue, then ingredient addition order as separate steps.
            - For SIMMERING (졸이기): include the transition from boil to simmer as a step.
            - For FRYING (튀기기): include oil temperature check method (젓가락 넣었을 때 거품 올라오면).

            STEPTIME & TOTAL TIME RULES:
            - "stepTimes": array of integers in SECONDS. MUST have exactly the same number of entries as "steps" — one entry per step, in the same order. If "steps" has 8 elements, "stepTimes" MUST also have exactly 8 elements.
              - 0 for prep/non-timed steps (chopping, mixing, plating).
              - Timed step examples: oil preheat 2min → 120, boiling 10min → 600, simmering 15min → 900.
            - "time": total cooking time as a plain INTEGER in MINUTES. NO units, no text, just a number.
              - If the user's request explicitly states a duration → use that number.
              - If not, sum all non-zero stepTimes and convert to minutes (round up to nearest minute).
              - If neither, estimate from step count: simple 2~3min/step, cooking 5~10min/step.
              - Minimum value: 1. Never 0 or null. Never a string like "30분".

            WHEN TO RETURN recipe_search:
            - User asks for a recipe by name, by ingredient, or by occasion.
            - User asks to modify the current recipe (더 맵게, 2인분으로, 간장 빼고 등).
            - User asks for an alternative that changes the recipe.

            WHEN TO RETURN chat:
            - Simple cooking Q&A that doesn't need a full recipe (보관법, 팁, 설명 등).
            - Friendly cooking-related conversation.

            RESPONSE FORMAT (always return valid JSON):

            Recipe (new or modified):
            {
              "type": "recipe_search",
              "keyword": "요리명",
              "response": "1~2문장의 친근한 한국어 안내",
              "recipe_data": {
                "title": "요리명",
                "category": "한식/양식/중식/일식/동남아식/디저트/기타 중 하나",
                "ingredients": ["재료1 분량", "재료2 분량", ...],
                "steps": ["1단계 상세 설명", "2단계 상세 설명", ...],
                "stepTimes": [120, 300, 0, 180],
                "difficulty": "쉬움/보통/어려움",
                "time": 35,
                "image_url": ""
              }
            }

            Non-cooking topic or non-recipe content:
            {
              "type": "not_recipe",
              "response": "저는 요리 전문 AI예요! 음식이나 요리 관련해서는 뭐든 도와드릴게요 😊"
            }

            Cooking Q&A / tips / conversation (no full recipe needed):
            {
              "type": "chat",
              "response": "친근하고 유용한 한국어 요리 조언"
            }
        """.trimIndent()
    }

    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!hasApiKey()) {
            android.util.Log.e("OpenAiManager", "transcribeAudio: OPENAI_API_KEY not set")
            onResult(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", OpenAiConfig.STT_MODEL)
            .addFormDataPart("language", "ko")
            .addFormDataPart(
                "prompt",
                "한국어 요리 관련 음성입니다. 요리 재료, 조리법, 음식 이름이 포함될 수 있습니다. " +
                        "발음이 불명확한 경우 요리 관련 단어로 보정해주세요. " +
                        "예: 김치찌개, 된장국, 볶음밥, 삼겹살, 파스타"
            )
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("OpenAiManager", "transcribeAudio network failure: ${e.message}", e)
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        onResult(json.optString("text"))
                    } else {
                        android.util.Log.e(
                            "OpenAiManager",
                            "transcribeAudio HTTP ${resp.code} (model=${OpenAiConfig.STT_MODEL}): ${parseApiError(resp, body)}"
                        )
                        onResult(null)
                    }
                }
            }
        })
    }

    suspend fun processChatRequestSuspend(
        userText: String,
        history: List<Pair<String, Boolean>>
    ): VoiceRequestResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            processChatRequest(userText, history) { result ->
                continuation.resume(result)
            }
        }
    }

    fun processChatRequest(
        userText: String,
        history: List<Pair<String, Boolean>>,
        onResult: (VoiceRequestResult) -> Unit
    ) {
        if (!hasApiKey()) {
            onResult(VoiceRequestResult.Error("OPENAI_API_KEY가 설정되어 있지 않습니다."))
            return
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", buildChatSystemPrompt())
            })
            history.takeLast(10).forEach { (text, isUser) ->
                put(JSONObject().apply {
                    put("role", if (isUser) "user" else "assistant")
                    put("content", text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })
        }

        // CHANGED: Use isIntentRequest() to choose a lightweight model for short commands
        val chosenModel = when {
            isComplexRequest(userText) -> OpenAiConfig.ADVANCED_MODEL
            isIntentRequest(userText) -> OpenAiConfig.INTENT_MODEL
            else -> OpenAiConfig.DEFAULT_TEXT_MODEL
        }

        val requestJson = JSONObject().apply {
            put("model", chosenModel)
            put("messages", messages)
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(VoiceRequestResult.Error("네트워크 오류: ${e.message ?: "unknown"}"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val responseBody = resp.body?.string()
                    if (!resp.isSuccessful || responseBody == null) {
                        onResult(VoiceRequestResult.Error(parseApiError(resp, responseBody)))
                        return@use
                    }

                    try {
                        val content = JSONObject(responseBody)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        val resultJson = JSONObject(content)
                        val type = resultJson.optString("type", "chat")
                        val responseMsg = resultJson.optString("response", "")

                        val normalizedRecipeData = normalizeRecipeData(resultJson.optJSONObject("recipe_data"))

                        onResult(
                            VoiceRequestResult.Success(
                                requestType = type,
                                keyword = resultJson.optString("keyword", ""),
                                responseMessage = responseMsg,
                                recipeData = normalizedRecipeData?.toString()
                            )
                        )
                    } catch (e: Exception) {
                        onResult(VoiceRequestResult.Error("응답 파싱 실패: ${e.message ?: "unknown"}"))
                    }
                }
            }
        })
    }

    fun processVoiceRequest(text: String, onResult: (VoiceRequestResult) -> Unit) {
        processChatRequest(text, emptyList(), onResult)
    }

    suspend fun analyzeRecipeImageSuspend(
        context: Context,
        imageUri: String
    ): VoiceRequestResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            if (!hasApiKey()) {
                if (continuation.isActive) {
                    continuation.resume(VoiceRequestResult.Error("OPENAI_API_KEY가 설정되어 있지 않습니다."))
                }
                return@suspendCancellableCoroutine
            }

            try {
                val uri = Uri.parse(imageUri)
                val bitmap = decodeImageForAnalysis(context, uri)
                    ?: throw IllegalStateException("이미지를 불러올 수 없습니다.")

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val requestJson = JSONObject().apply {
                    put("model", OpenAiConfig.IMAGE_MODEL)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put(
                                        "text",
                                        """
                                        Analyze this cooking image and return JSON only.

                                        ABSOLUTE RULE — KOREAN ONLY:
                                        Every single output field MUST be in Korean without exception.
                                        This includes: title, keyword, response, category, ingredients, steps,
                                        difficulty, and time.
                                        If the source image or document is in English, Japanese, Chinese,
                                        or any other language — translate EVERYTHING to Korean.
                                        Transliterate if no Korean equivalent exists (e.g. "파스타", "스테이크").
                                        A response containing ANY non-Korean characters in value fields
                                        (except numbers and units) is considered a failure.

                                        RULES:
                                        1. If the image contains clear food, ingredients, or a cooking menu, extract/infer the recipe.
                                        2. If the image is NOT food-related (e.g. keyboard, shoe, scenery) or too blurry to identify anything, return:
                                           {
                                             "type": "not_recipe",
                                             "response": "식재료를 찾을 수 없어요. 요리 재료가 잘 보이게 다시 찍어주시겠어요? 😊"
                                           }
                                        3. Do NOT hallucinate a recipe if food is not present.

                                        Return exactly this shape for recipes:
                                        {
                                          "type": "recipe_search",
                                          "keyword": "dish name",
                                          "response": "short Korean message",
                                          "recipe_data": {
                                            "title": "dish name",
                                            "category": "한식/양식/중식/일식/동남아식/디저트/기타 중 하나",
                                            "ingredients": ["ingredient"],
                                            "steps": ["step"],
                                            "stepTimes": [120, 300, 0],
                                            "difficulty": "쉬움/보통/어려움",
                                            "time": 20,
                                            "image_url": "$imageUri"
                                          }
                                        }
                                        CATEGORY RULES:
                                        - Maratang (마라탕) is CHINESE (중식).
                                        - Tteokbokki (떡복이) is KOREAN (한식).
                                        - Pizza/Pasta is WESTERN (양식).
                                        - Sushi is JAPANESE (일식).
                                        If the image is a recipe text image, extract the recipe.
                                        If the image is food without text, infer a likely recipe.
                                        """.trimIndent()
                                    )
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$base64Image")
                                            put("detail", "high")
                                        }
                                    )
                                })
                            })
                        })
                    })
                    put("response_format", JSONObject().apply {
                        put("type", "json_object")
                    })
                }

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resume(VoiceRequestResult.Error("네트워크 오류: ${e.message ?: "unknown"}"))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { resp ->
                            val responseBody = resp.body?.string()
                            if (!resp.isSuccessful || responseBody == null) {
                                if (continuation.isActive) {
                                    continuation.resume(VoiceRequestResult.Error(parseApiError(resp, responseBody)))
                                }
                                return@use
                            }

                            try {
                                val content = JSONObject(responseBody)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                val resultJson = JSONObject(content)
                                val recipeJson = normalizeRecipeImageResult(resultJson, imageUri)
                                val keyword = resultJson.optString("keyword")
                                    .ifBlank { recipeJson.optString("title") }
                                    .ifBlank { "이미지 레시피" }

                                if (continuation.isActive) {
                                    continuation.resume(
                                        VoiceRequestResult.Success(
                                            requestType = resultJson.optString("type", "recipe_search"),
                                            keyword = keyword,
                                            responseMessage = resultJson.optString(
                                                "response",
                                                "이미지에서 레시피를 분석했어요."
                                            ),
                                            recipeData = recipeJson.toString()
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                if (continuation.isActive) {
                                    continuation.resume(VoiceRequestResult.Error("응답 파싱 실패: ${e.message ?: "unknown"}"))
                                }
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(VoiceRequestResult.Error("이미지 처리 실패: ${e.message ?: "unknown"}"))
                }
            }
        }
    }

    private fun decodeImageForAnalysis(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // Pass 1: read dimensions only to calculate the down-sample factor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = 1024
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        // ADDED: TASK 2 - Fixed decodeImageForAnalysis with EXIF rotation correction
        val rotationDegrees = resolver.openInputStream(uri)?.use { input ->
            try {
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
        } ?: 0f

        // Pass 3: decode and scale
        val decoded = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: return null

        val scaled = Bitmap.createScaledBitmap(
            decoded,
            if (decoded.width > decoded.height) maxDimension else (decoded.width * maxDimension / decoded.height),
            if (decoded.height > decoded.width) maxDimension else (decoded.height * maxDimension / decoded.width),
            true
        ).also { if (it != decoded) decoded.recycle() }

        // ADDED: Apply rotation correction if needed
        return if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
                .also { scaled.recycle() }
        } else {
            scaled
        }
    }

    private fun normalizeRecipeImageResult(resultJson: JSONObject, imageUri: String): JSONObject {
        val recipeJson = resultJson.optJSONObject("recipe_data") ?: JSONObject().apply {
            put("title", resultJson.optString("title"))
            put("category", resultJson.optString("category"))
            put("ingredients", resultJson.optJSONArray("ingredients") ?: JSONArray())
            put("steps", resultJson.optJSONArray("steps") ?: JSONArray())
            put("difficulty", resultJson.optString("difficulty"))
            put("time", resultJson.optString("time"))
        }

        if (recipeJson.optString("title").isBlank()) {
            recipeJson.put("title", resultJson.optString("keyword", "이미지 레시피"))
        }
        val currentImageUrl = recipeJson.optString("image_url")
        if (currentImageUrl.isBlank() || currentImageUrl == "없음") {
            recipeJson.put("image_url", imageUri)
        }

        return normalizeRecipeData(recipeJson) ?: recipeJson
    }

    private fun normalizeRecipeData(recipeJson: JSONObject?): JSONObject? {
        if (recipeJson == null) return null

        recipeJson.put(
            "category",
            normalizeRecipeCategory(
                rawCategory = recipeJson.optString("category"),
                title = recipeJson.optString("title")
            )
        )
        return recipeJson
    }

    /**
     * Routes to ADVANCED_MODEL when the request is genuinely complex.
     */
    private fun isComplexRequest(text: String): Boolean {
        val lower = text.lowercase()
        val constraintScore = listOf(
            "알레르기", "못 먹", "안 먹", "빼고", "없이", "채식", "비건",
            "글루텐", "유제품", "분 안에", "분 이내", "재료만", "장비",
            "오븐 없이", "냉장고에 있는"
        ).count { lower.contains(it) }

        val reasoningScore = listOf(
            "왜", "실패", "분석", "원인", "이유", "계획", "영양", "칼로리",
            "건강", "다이어트", "대체", "substitut", "improve"
        ).count { lower.contains(it) }

        return constraintScore >= 2 || reasoningScore >= 2 || text.length > 200
    }

    // ADDED: TASK 1 - lightweight model for short intent commands
    private fun isIntentRequest(text: String): Boolean {
        val lower = text.lowercase()
        val keywords = listOf("다음","이전","시작","정지","멈춰",
            "타이머","재개","처음","끝","완료","보여줘","알려줘")
        return text.length < 30 && keywords.any { lower.contains(it) }
    }
}

sealed class VoiceRequestResult {
    data class Success(
        val requestType: String,
        val keyword: String,
        val responseMessage: String,
        val recipeData: String? = null
    ) : VoiceRequestResult()

    data class Error(val message: String) : VoiceRequestResult()
}
