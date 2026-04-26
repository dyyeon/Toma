package com.capstone.toma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
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

    /**
     * [1단계: STT] 음성을 텍스트로 변환
     * prompt를 추가하여 요리 관련 단어 인식률을 높임
     */
    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!audioFile.exists()) {
            onResult(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mpeg".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ko")
            // 요리 관련 컨텍스트를 주어 인식률 향상
            .addFormDataPart("prompt", "요리, 레시피, 식재료, 조리법, 토마, TOMA, 주방 어시스턴트")
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
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    val text = JSONObject(resBody).optString("text")
                    onResult(text)
                } else {
                    onResult(null)
                }
            }
        })
    }

    /**
     * Coroutine-friendly suspend version of processChatRequest
     */
    suspend fun processChatRequestSuspend(
        userText: String,
        history: List<Pair<String, Boolean>>
    ): VoiceRequestResult = suspendCancellableCoroutine { continuation ->
        processChatRequest(userText, history) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    /**
     * [2단계: LLM] 요리 전문 AI 정체성 강화
     */
    fun processChatRequest(
        userText: String,
        history: List<Pair<String, Boolean>>,
        onResult: (VoiceRequestResult) -> Unit
    ) {
        val systemPrompt = """
            당신은 요리 전문 AI 어시스턴트 '토마(TOMA)'입니다.
            사용자의 요리 여정을 돕는 친절하고 전문적인 셰프 역할을 수행하세요.
            
            [응답 지침]
            1. 질문 의도 파악:
               - 시간/난이도 질문: 예상 소요 시간과 요리의 난이도를 구체적으로 설명하세요. (예: "약 15분 정도 걸리는 아주 쉬운 요리예요.")
               - 대체 재료 질문: 해당 재료가 없을 때 사용할 수 있는 대체품과 그에 따른 맛의 변화를 알려주세요.
               - 메뉴 추천/검색: 해당 요리의 특징, 어울리는 상황, 맛의 포인트를 설명하세요.
            2. 정체성 유지: 요리/식재료와 무관한 질문은 정중히 거절하고 요리 관련 대화로 유도하세요.
            3. 대화 흐름 제어:
               - 분석 완료(`recipe_search`): 요리명, 주요 재료, 특징을 요약하고 "조리 가이드를 시작할까요?"라고 반드시 물어보세요.
               - 조리 시작(`recipe_navigation`): 사용자가 긍정하면 즉시 이동 타입을 반환하세요.
            
            [응답 형식 (JSON 강제)]
            반드시 아래 구조의 JSON으로만 응답해야 합니다:
            {
              "type": "chat" (일반대화) | "recipe_search" (레시피 분석결과) | "recipe_navigation" (화면이동),
              "keyword": "핵심 요리명",
              "response": "사용자에게 전달할 풍부하고 친절한 답변 (마크다운 사용 가능)",
              "recipe_data": {
                "title": "요리명",
                "ingredients": ["재료(용량)"],
                "steps": ["단계별 설명"],
                "difficulty": "쉬움/보통/어려움",
                "time": "소요 시간"
              } (레시피 정보가 확인된 경우 최대한 포함)
            }
        """.trimIndent()

        val messages = org.json.JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            history.takeLast(10).forEach { (text, isUser) ->
                put(JSONObject().apply { 
                    put("role", if (isUser) "user" else "assistant")
                    put("content", text)
                })
            }
            put(JSONObject().apply { put("role", "user"); put("content", userText) })
        }

        val json = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", messages)
            put("temperature", 0.7) // 답변의 풍부함을 위해 창의성 소폭 상향
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }

        val requestBody = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(VoiceRequestResult.Error("네트워크 오류가 발생했습니다."))
            }
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    try {
                        val resultJson = JSONObject(JSONObject(resBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
                        onResult(VoiceRequestResult.Success(
                            requestType = resultJson.optString("type", "chat"),
                            keyword = resultJson.optString("keyword", ""),
                            responseMessage = resultJson.optString("response", ""),
                            recipeData = resultJson.optJSONObject("recipe_data")?.toString()
                        ))
                    } catch (e: Exception) {
                        onResult(VoiceRequestResult.Error("분석 오류가 발생했습니다."))
                    }
                } else {
                    onResult(VoiceRequestResult.Error("API 응답 에러"))
                }
            }
        })
    }

    fun processVoiceRequest(userText: String, onResult: (VoiceRequestResult) -> Unit) {
        processChatRequest(userText, emptyList(), onResult)
    }

    fun analyzeIntent(userText: String, onResult: (String?) -> Unit) {
        val systemPrompt = "요리 단계 진행 의도 분석: [다음, 이전, 타이머, 재료확인, 알수없음] 중 하나만 응답."
        val json = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userText) })
            })
            put("temperature", 0.1)
        }

        val requestBody = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult("에러") }
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    val content = JSONObject(resBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                    onResult(content)
                } else { onResult("에러") }
            }
        })
    }

    /**
     * [3단계: Vision] 이미지 기반 레시피 분석
     */
    suspend fun analyzeRecipeImageSuspend(
        context: Context,
        imageUri: String
    ): VoiceRequestResult = suspendCancellableCoroutine { continuation ->
        try {
            val uri = Uri.parse(imageUri)
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: throw Exception("이미지를 불러올 수 없습니다.")

            // 이미지 크기 최적화 (API 비용 및 속도)
            val resizedBitmap = if (bitmap.width > 1024 || bitmap.height > 1024) {
                val scale = 1024f / Math.max(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap

            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val systemPrompt = """
                당신은 요리 사진 분석 전문가 '토마'입니다.
                이미지를 분석하여 다음 중 하나를 수행하세요:
                1. 완성된 요리 사진이라면: 어떤 요리인지 맞히고, 해당 요리의 일반적인 레시피를 생성하세요.
                2. 레시피 텍스트 사진이라면: 텍스트를 OCR하여 구조화된 레시피로 만드세요.
                
                반드시 다음 JSON 형식으로 응답하세요:
                {
                  "type": "recipe_search",
                  "keyword": "요리명",
                  "response": "사진을 분석해보니 [요리명]이네요!\n\n[주요 재료]: [재료 요약]\n[특징]: [맛/스타일 특징]\n\n이 레시피로 안내를 시작할까요?",
                  "recipe_data": {
                    "title": "요리명",
                    "ingredients": ["재료1 (용량)", "재료2 (용량)"],
                    "steps": ["1단계 설명", "2단계 설명"],
                    "difficulty": "쉬움/보통/어려움",
                    "time": "소요 시간"
                  }
                }
            """.trimIndent()

            val json = JSONObject().apply {
                put("model", "gpt-4o") // Vision을 위해 gpt-4o 사용
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", org.json.JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", systemPrompt) })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$base64Image")
                                })
                            })
                        })
                    })
                })
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }

            val requestBody = RequestBody.create("application/json".toMediaType(), json.toString())
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(VoiceRequestResult.Error("네트워크 연결 실패: ${e.message}"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val resBody = response.body?.string()
                    if (response.isSuccessful && resBody != null) {
                        try {
                            val content = JSONObject(resBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                            val resultJson = JSONObject(content)
                            if (continuation.isActive) continuation.resume(VoiceRequestResult.Success(
                                requestType = resultJson.optString("type", "recipe_search"),
                                keyword = resultJson.optString("keyword", "요리"),
                                responseMessage = resultJson.optString("response", ""),
                                recipeData = resultJson.optJSONObject("recipe_data")?.toString()
                            ))
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(VoiceRequestResult.Error("결과 분석 실패: ${e.message}"))
                        }
                    } else {
                        if (continuation.isActive) continuation.resume(VoiceRequestResult.Error("API 응답 실패 (코드: ${response.code})"))
                    }
                }
            })
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(VoiceRequestResult.Error("이미지 처리 실패: ${e.message}"))
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
