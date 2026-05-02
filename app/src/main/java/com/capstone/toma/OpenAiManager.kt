package com.capstone.toma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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
        You are TOMA, a cooking assistant.
        Respond in Korean.

        Rules:
        1. If the user is discussing menus, ingredients, cooking methods, substitutions, time, or difficulty, answer helpfully.
        2. If a concrete recipe is ready and the user can move to the next screen, return type "recipe_search".
        3. If the user clearly agrees to proceed with the already proposed recipe, return type "recipe_navigation".
        4. Otherwise return type "chat".
        5. Return JSON only.

        JSON format:
        {
          "type": "chat" | "recipe_search" | "recipe_navigation",
          "keyword": "dish name",
          "response": "Korean response for the user",
          "recipe_data": {
            "title": "dish name",
            "category": "한식/중식/양식 등",
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
            .addFormDataPart("prompt", "요리, 레시피, 식재료, 조리법, 토마, TOMA")
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
            onResult(VoiceRequestResult.Error("OPENAI_API_KEY가 설정되지 않았습니다."))
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
                    onResult(
                        VoiceRequestResult.Success(
                            requestType = resultJson.optString("type", "chat"),
                            keyword = resultJson.optString("keyword", ""),
                            responseMessage = resultJson.optString("response", ""),
                            recipeData = resultJson.optJSONObject("recipe_data")?.toString()
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
            onResult("에러")
            return
        }

        val requestJson = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "요리 단계 진행 의도를 분석하세요. [다음, 이전, 타이머, 재료확인, 알수없음] 중 하나만 답변.")
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
                onResult("에러")
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
                    onResult("에러")
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
                continuation.resume(VoiceRequestResult.Error("OPENAI_API_KEY가 설정되지 않았습니다."))
            }
            return@suspendCancellableCoroutine
        }

        try {
            val uri = Uri.parse(imageUri)
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
                ?: throw IllegalStateException("이미지를 불러올 수 없습니다.")

            val resizedBitmap = if (bitmap.width > 1024 || bitmap.height > 1024) {
                val scale = 1024f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
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
                                    Use type "recipe_search".
                                    Include title, category, ingredients, steps, difficulty, and time when possible.
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

                        if (continuation.isActive) {
                            continuation.resume(
                                VoiceRequestResult.Success(
                                    requestType = resultJson.optString("type", "recipe_search"),
                                    keyword = resultJson.optString("keyword", ""),
                                    responseMessage = resultJson.optString("response", ""),
                                    recipeData = resultJson.optJSONObject("recipe_data")?.toString()
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

sealed class VoiceRequestResult {
    data class Success(
        val requestType: String,
        val keyword: String,
        val responseMessage: String,
        val recipeData: String? = null
    ) : VoiceRequestResult()

    data class Error(val message: String) : VoiceRequestResult()
}
