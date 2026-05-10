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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        } catch (e: Exception) { "API 요청 실패 (${response.code})" }
    }

    /**
     * 프롬프트 보강: JSON 형식 강제 및 레시피 데이터 구조 명시
     */
    private fun buildChatSystemPrompt(): String {
        return """
            You are 'Toma', a warm and knowledgeable AI cooking assistant.
            
            CRITICAL RULES:
            1. **Response must be in valid JSON format.**
            2. ALL output fields MUST be in Korean.
            3. If the user asks for a recipe, you MUST return "type": "recipe_search" with full "recipe_data".
            
            [JSON STRUCTURE]
            - For Recipes:
            {
              "type": "recipe_search",
              "keyword": "요리명",
              "response": "친절한 안내 문구",
              "recipe_data": {
                "title": "요리명",
                "category": "한식/중식/일식/양식/디저트 등",
                "ingredients": ["재료1 분량", "재료2 분량", ...],
                "steps": ["1단계 상세 설명", "2단계 상세 설명", ...],
                "difficulty": "쉬움/보통/어려움",
                "time": "00분"
              }
            }
            - For General Chat:
            {
              "type": "chat",
              "response": "한국어 답변"
            }
        """.trimIndent()
    }

    /**
     * STT (Whisper) - FIXED: audio/m4a MIME type
     */
    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!hasApiKey()) { onResult(null); return }
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ko")
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onResult(null)
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    onResult(JSONObject(body).optString("text"))
                } else { onResult(null) }
            }
        })
    }

    /**
     * TomaNavHost에서 사용하는 Suspend 함수
     */
    suspend fun processChatRequestSuspend(
        userText: String,
        history: List<Pair<String, Boolean>>
    ): VoiceRequestResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            processChatRequest(userText, history) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    fun processChatRequest(
        userText: String,
        history: List<Pair<String, Boolean>>,
        onResult: (VoiceRequestResult) -> Unit
    ) {
        if (!hasApiKey()) { onResult(VoiceRequestResult.Error("API 키 없음")); return }

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", buildChatSystemPrompt()) })
            history.takeLast(10).forEach { (text, isUser) ->
                put(JSONObject().apply {
                    put("role", if (isUser) "user" else "assistant")
                    put("content", text)
                })
            }
            put(JSONObject().apply { put("role", "user"); put("content", userText) })
        }

        // OpenAiConfig.ADVANCED_MODEL 사용 (gpt-5.5)
        val chosenModel = if (isComplexRequest(userText)) OpenAiConfig.ADVANCED_MODEL
        else OpenAiConfig.DEFAULT_TEXT_MODEL

        val requestJson = JSONObject().apply {
            put("model", chosenModel)
            put("messages", messages)
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onResult(VoiceRequestResult.Error(e.message ?: "Network Error"))
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    onResult(VoiceRequestResult.Error(parseApiError(response, responseBody)))
                    return
                }
                try {
                    val content = JSONObject(responseBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    val resultJson = JSONObject(content)
                    onResult(VoiceRequestResult.Success(
                        requestType = resultJson.optString("type", "chat"),
                        keyword = resultJson.optString("keyword", ""),
                        responseMessage = resultJson.optString("response", ""),
                        recipeData = normalizeRecipeData(resultJson.optJSONObject("recipe_data"))?.toString()
                    ))
                } catch (e: Exception) { onResult(VoiceRequestResult.Error("응답 파싱 오류")) }
            }
        })
    }

    suspend fun analyzeRecipeImageSuspend(context: Context, imageUri: String): VoiceRequestResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                val uri = Uri.parse(imageUri)
                val bitmap = decodeImageForAnalysis(context, uri) ?: throw Exception("이미지 로드 실패")
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val requestJson = JSONObject().apply {
                    put("model", OpenAiConfig.IMAGE_MODEL)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "이 이미지를 분석해서 요리 레시피 JSON 데이터를 만들어줘. 한국어로 응답해.")
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$base64Image") })
                                })
                            })
                        })
                    })
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(VoiceRequestResult.Error(e.message ?: "Error")) }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val content = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                            val resultJson = JSONObject(content)
                            val recipeJson = normalizeRecipeImageResult(resultJson, imageUri)
                            if (continuation.isActive) continuation.resume(VoiceRequestResult.Success("recipe_search", "이미지 레시피", "분석 완료", recipeJson.toString()))
                        } else { if (continuation.isActive) continuation.resume(VoiceRequestResult.Error("API Error")) }
                    }
                })
            } catch (e: Exception) { if (continuation.isActive) continuation.resume(VoiceRequestResult.Error(e.message ?: "Exception")) }
        }
    }

    private fun decodeImageForAnalysis(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = if (bounds.outWidth > 1024) bounds.outWidth / 1024 else 1
        return resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        }
    }

    private fun normalizeRecipeImageResult(resultJson: JSONObject, imageUri: String): JSONObject {
        val recipeJson = resultJson.optJSONObject("recipe_data") ?: JSONObject()
        if (recipeJson.optString("image_url").isBlank()) recipeJson.put("image_url", imageUri)
        return normalizeRecipeData(recipeJson) ?: recipeJson
    }

    private fun normalizeRecipeData(recipeJson: JSONObject?): JSONObject? {
        if (recipeJson == null) return null
        recipeJson.put("category", normalizeRecipeCategory(recipeJson.optString("category"), recipeJson.optString("title")))
        return recipeJson
    }

    private fun isComplexRequest(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("알레르기") || lower.contains("빼고") || lower.contains("왜") || text.length > 200
    }
}

sealed class VoiceRequestResult {
    data class Success(val requestType: String, val keyword: String, val responseMessage: String, val recipeData: String? = null) : VoiceRequestResult()
    data class Error(val message: String) : VoiceRequestResult()
}