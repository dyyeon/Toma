package com.capstone.toma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

            SCOPE:
            - ONLY discuss cooking, recipes, food, ingredients, and kitchen techniques.
            - If asked about unrelated topics (weather, news, sports, etc.), respond with type "not_recipe".
            - Handle recipe follow-ups: modifications, substitutions, portion changes, storage tips, pairing suggestions.

            CRITICAL RULES:
            - ALL output fields MUST be in Korean. No English in title, ingredients, or steps.
            - ingredients: exact quantities required (e.g. "계란 2개", "간장 1큰술", "소금 약간").
            - steps: MINIMUM 7 steps. Each step = ONE action only. Always include:
              * Heat level (강불 / 중불 / 약불)
              * Timing (몇 분간)
              * Visual/texture cue (황금색이 될 때까지, 걸쭉해질 때까지 등)
            - Never combine multiple actions into a single step.

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
                "category": "한식/양식/중식/일식/디저트/기타 중 하나",
                "ingredients": ["재료1 분량", "재료2 분량", ...],
                "steps": ["1단계 상세 설명", "2단계 상세 설명", ...],
                "difficulty": "쉬움/보통/어려움",
                "time": "00분",
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
            onResult(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ko")
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    onResult(json.optString("text"))
                } else {
                    onResult(null)
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

        val requestJson = JSONObject().apply {
            put("model", "gpt-4o")
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
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    onResult(VoiceRequestResult.Error(parseApiError(response, responseBody)))
                    return
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
        })
    }

    fun processVoiceRequest(text: String, onResult: (VoiceRequestResult) -> Unit) {
        processChatRequest(text, emptyList(), onResult)
    }

    fun analyzeIntent(text: String, onResult: (String?) -> Unit) {
        if (!hasApiKey()) {
            onResult(null)
            return
        }

        val requestJson = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Identify the intent of the user's message. Reply with a single word: RECIPE_SEARCH, TIMER_SET, or OTHER.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
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
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val content = JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    onResult(content.trim())
                } else {
                    onResult(null)
                }
            }
        })
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
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val requestJson = JSONObject().apply {
                    put("model", "gpt-4o")
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
                                        Respond in Korean.
                                        Return exactly this shape:
                                        {
                                          "type": "recipe_search",
                                          "keyword": "dish name",
                                          "response": "short Korean message",
                                          "recipe_data": {
                                            "title": "dish name",
                                            "category": "한식/양식/중식/일식/디저트/기타 중 하나",
                                            "ingredients": ["ingredient"],
                                            "steps": ["step"],
                                            "difficulty": "쉬움/보통/어려움",
                                            "time": "20분",
                                            "image_url": "$imageUri"
                                          }
                                        }
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
                        val responseBody = response.body?.string()
                        if (!response.isSuccessful || responseBody == null) {
                            if (continuation.isActive) {
                                continuation.resume(VoiceRequestResult.Error(parseApiError(response, responseBody)))
                            }
                            return
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
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = 1024
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val decoded = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null

        return Bitmap.createScaledBitmap(
            decoded,
            if (decoded.width > decoded.height) maxDimension else (decoded.width * maxDimension / decoded.height),
            if (decoded.height > decoded.width) maxDimension else (decoded.height * maxDimension / decoded.width),
            true
        ).also {
            if (it != decoded) decoded.recycle()
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
