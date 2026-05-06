package com.capstone.toma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.capstone.toma.model.normalizeRecipeCategory
import kotlinx.coroutines.suspendCancellableCoroutine
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

    private fun parseApiError(response: Response, responseBody: String?): String {
        val message = runCatching {
            JSONObject(responseBody ?: "")
                .optJSONObject("error")
                ?.optString("message")
                .orEmpty()
        }.getOrDefault("")

        return if (message.isNotBlank()) {
            "API 응답 실패 (${response.code}): $message"
        } else {
            "API 응답 실패 (${response.code})"
        }
    }

    private fun buildChatSystemPrompt(): String = """
        You are TOMA, a professional cooking assistant.
        Respond in Korean.

        [Task]
        1. Discuss menus, ingredients, cooking methods, substitutions, time, or difficulty helpfully.
        2. Analyze provided text (scraped from web/YouTube) or user messages to extract structured recipe data.
        3. If a concrete recipe is ready (either from text provided or user request), return type "recipe_search".
        4. If the user agrees to proceed with a recipe, return type "recipe_navigation".
        5. Otherwise return type "chat".

        [Rules]
        - ALWAYS return JSON only.
        - When "recipe_search" is returned, you MUST fill the "recipe_data" object based on the context.
        - If text is provided with "URL", "제목", and "내용", prioritize extracting the recipe from that "내용".

        [JSON format]
        {
          "type": "chat" | "recipe_search" | "recipe_navigation",
          "keyword": "dish name",
          "response": "Brief Korean response for the user",
          "recipe_data": {
            "title": "dish name",
            "category": "한식/양식/중식/일식/디저트/기타",
            "ingredients": ["item 1", "item 2"],
            "steps": ["step 1", "step 2"],
            "difficulty": "쉬움/보통/어려움",
            "time": "20분",
            "image_url": "optional image url"
          }
        }
    """.trimIndent()

    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!audioFile.exists() || !hasApiKey()) {
            onResult(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mpeg".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ko")
            .addFormDataPart("prompt", "요리, 레시피, 식재료, 조리법, 타이머, TOMA")
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
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    onResult(JSONObject(responseBody).optString("text"))
                } else {
                    onResult(null)
                }
            }
        })
    }

    suspend fun processChatRequestSuspend(
        userText: String,
        history: List<Pair<String, Boolean>>
    ): VoiceRequestResult = suspendCancellableCoroutine { continuation ->
        processChatRequest(userText, history) { result ->
            if (continuation.isActive) continuation.resume(result)
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
            put("model", "gpt-4o-mini")
            put("messages", messages)
            put("temperature", 0.5)
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
                    val normalizedRecipeData = normalizeRecipeData(resultJson.optJSONObject("recipe_data"))
                    onResult(
                        VoiceRequestResult.Success(
                            requestType = resultJson.optString("type", "chat"),
                            keyword = resultJson.optString("keyword", ""),
                            responseMessage = resultJson.optString("response", ""),
                            recipeData = normalizedRecipeData?.toString()
                        )
                    )
                } catch (e: Exception) {
                    onResult(VoiceRequestResult.Error("응답 파싱 실패: ${e.message ?: "unknown"}"))
                }
            }
        })
    }

    fun processVoiceRequest(userText: String, onResult: (VoiceRequestResult) -> Unit) {
        processChatRequest(userText, emptyList(), onResult)
    }

    fun analyzeIntent(userText: String, onResult: (String?) -> Unit) {
        if (!hasApiKey()) {
            onResult("오류")
            return
        }

        val requestJson = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "요리 단계 진행 의도를 분석하세요. [다음, 이전, 타이머, 재료확인, 알수없음] 중 하나만 답하세요.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
            put("temperature", 0.1)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult("오류")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    val content = JSONObject(responseBody)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    onResult(content)
                } else {
                    onResult("오류")
                }
            }
        })
    }

    suspend fun analyzeRecipeImageSuspend(
        context: Context,
        imageUri: String
    ): VoiceRequestResult = suspendCancellableCoroutine { continuation ->
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
        while (
            bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val decoded = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        if (decoded.width <= maxDimension && decoded.height <= maxDimension) {
            return decoded
        }

        val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
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
        if (recipeJson.optString("image_url").isBlank()) {
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
