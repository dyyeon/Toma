package com.capstone.toma

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
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
            당신의 유일한 목적은 사용자의 요리를 돕는 것입니다. 
            
            [엄격한 정체성 규칙]
            1. 요리, 레시피, 식재료, 식단과 관련 없는 질문(연예, 노래, 정치 등)에는 절대 대답하지 마세요.
            2. 관련 없는 요청이 오면 "저는 요리 도우미라서 그건 잘 몰라요. 대신 맛있는 레시피를 찾아드릴까요?"와 같이 답변하세요.
            3. 사용자가 메뉴를 언급하면 즉시 요리 모드로 인식하여 특징을 설명하고 레시피 안내 여부를 묻습니다.
            4. [링크 분석 특화 지침]:
               - 콘텐츠 성격 판별: recipe(조리), guide(팁/노하우), article(정보), non_actionable(잡담/광고) 중 하나로 분류하세요.
               - 본문 정제: 인사말, 후기, 광고, 협찬 문구 등은 모두 제거하고 실제 도움되는 '실전 정보'만 남기세요.
               - 정보 추출: 제목, 한 줄 요약, 핵심 포인트, 재료, 도구, 시간, 난이도를 정확히 추출하세요. 원문에 없는 수치는 추정하지 마세요.
               - 단계 재구성: 사용자가 바로 따라할 수 있게 행동 중심의 단계형 가이드로 다시 작성하세요.
            5. [출력 형식]:
               - 분석 완료 시 반드시 "분석을 완료했어요! [요리명] 레시피 안내를 시작할까요?" 문구를 사용하세요.
               - 하단에 반드시 JSON { "type": "recipe_search", "keyword": "요리명" }을 포함하세요.
            
            [응답 가이드라인]
            - "레시피 안내를 시작할까요?"라는 문구는 사용자가 특정 메뉴를 확정했을 때만 포함하세요.
            - 항상 친근하고 정중한 말투를 사용하세요.
            
            [응답 형식 (JSON)]
            {
              "type": "recipe_search", "chat", 또는 "menu_recommend",
              "keyword": "결정된 요리 이름 또는 검색어",
              "response": "사용자에게 줄 대답",
              "recipe_data": {
                "ingredients": ["재료1 (용량)", "재료2 (용량)"],
                "steps": ["1단계 설명", "2단계 설명"],
                "difficulty": "쉬움/보통/어려움",
                "time": "예상 소요 시간 (예: 20분)",
                "image_url": "이미지 URL (있을 경우)"
              } (type이 recipe_search일 경우 반드시 포함)
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
            put("temperature", 0.5) // 정체성 유지를 위해 조금 더 일관성 있게 조절
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
