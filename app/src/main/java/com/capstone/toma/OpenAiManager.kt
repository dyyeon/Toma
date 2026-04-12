package com.capstone.toma

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKey = BuildConfig.OPENAI_API_KEY

    /**
     * [1단계: STT] 음성을 텍스트로 변환
     */
    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!audioFile.exists()) {
            Log.e("OpenAiManager", "파일 없음: ${audioFile.absolutePath}")
            onResult(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mpeg".toMediaType()))
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
                Log.e("OpenAiManager", "STT 실패: ${e.message}")
                onResult(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    val text = JSONObject(resBody).optString("text")
                    Log.d("OpenAiManager", "STT 성공: $text")
                    onResult(text)
                } else {
                    Log.e("OpenAiManager", "STT 응답 에러: ${response.code} / $resBody")
                    onResult(null)
                }
            }
        })
    }

    /**
     * [2단계: LLM] 음성 가이드 화면용 - 레시피 검색/메뉴 추천 요청 처리
     */
    fun processVoiceRequest(userText: String, onResult: (VoiceRequestResult) -> Unit) {
        val systemPrompt = """
            당신은 요리 보조 AI '토마'입니다. 
            사용자의 음성 요청을 분석하여 적절한 응답을 생성하세요.
            
            [요청 유형]
            1. 레시피 검색: 특정 음식의 레시피를 요청 (예: "김치볶음밥 레시피 알려줘", "떡볶이 만드는 법")
            2. 메뉴 추천: 일반적인 메뉴 추천 요청 (예: "저녁 메뉴 추천해줘", "간단한 요리 추천")
            3. 재료 기반 검색: 특정 재료로 만들 수 있는 요리 (예: "김치로 뭐 만들어?", "계란 요리")
            4. 빠른/쉬운 요리: 시간이나 난이도 기반 (예: "빨리 만들 수 있는 거", "초보자용 레시피")
            
            응답 형식 (JSON):
            {
              "type": "recipe_search" 또는 "menu_recommend" 또는 "ingredient_search" 또는 "quick_easy",
              "keyword": "검색할 키워드 (예: 김치볶음밥, 저녁메뉴, 김치, 간단한요리)",
              "response": "사용자에게 보여줄 친근한 응답 메시지"
            }
        """.trimIndent()

        val json = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userText) })
            })
            put("temperature", 0.3)
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
                Log.e("OpenAiManager", "LLM 네트워크 오류: ${e.message}")
                onResult(VoiceRequestResult.Error("네트워크 오류가 발생했습니다."))
            }
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    try {
                        val content = JSONObject(resBody)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")

                        val resultJson = JSONObject(content)
                        val type = resultJson.optString("type", "unknown")
                        val keyword = resultJson.optString("keyword", "")
                        val responseMsg = resultJson.optString("response", "요청을 처리했습니다.")

                        Log.d("OpenAiManager", "LLM 분석 성공 - type: $type, keyword: $keyword")

                        onResult(VoiceRequestResult.Success(
                            requestType = type,
                            keyword = keyword,
                            responseMessage = responseMsg
                        ))

                    } catch (e: Exception) {
                        Log.e("OpenAiManager", "LLM 파싱 에러: ${e.message}\n응답: $resBody")
                        onResult(VoiceRequestResult.Error("응답 처리 중 오류가 발생했습니다."))
                    }
                } else {
                    Log.e("OpenAiManager", "🚨 LLM 응답 에러 (코드: ${response.code}) 🚨\n내용: $resBody")
                    onResult(VoiceRequestResult.Error("API 오류 (${response.code})"))
                }
            }
        })
    }

    /**
     * [3단계: LLM] 레시피 단계 진행용 의도 분석 (기존 기능 유지)
     */
    fun analyzeIntent(userText: String, onResult: (String?) -> Unit) {
        val systemPrompt = """
            당신은 요리 보조 AI '토마'입니다. 사용자의 입력을 분석해 단 한 단어로만 응답하세요.
            
            [의도 분류 규칙]
            1. 다음: 단계 이동, 완료 보고 (예: "다음", "그다음", "다했어", "넘어가자", "알았어")
            2. 이전: 다시 듣기, 뒤로 가기 (예: "이전", "다시", "뒤로", "뭐라고?", "못들었어")
            3. 타이머: 시간 설정 (예: "3분 재줘", "타이머", "알람 시작")
            4. 재료확인: 재료 문의 (예: "뭐뭐 필요해?", "재료 뭐야", "준비물")
            5. 알수없음: 위 의도와 상관없는 인사나 소음 (예: "안녕", "날씨 좋아")

            반드시 [다음, 이전, 타이머, 재료확인, 알수없음] 중 하나만 출력하세요.
        """.trimIndent()

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
            override fun onFailure(call: Call, e: IOException) {
                Log.e("OpenAiManager", "LLM 네트워크 오류: ${e.message}")
                onResult("분석오류")
            }
            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    try {
                        val content = JSONObject(resBody).getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content").trim()
                        onResult(content)
                    } catch (e: Exception) {
                        Log.e("OpenAiManager", "LLM 파싱 에러: $resBody")
                        onResult("파싱오류")
                    }
                } else {
                    Log.e("OpenAiManager", "🚨 LLM 응답 에러 (코드: ${response.code}) 🚨\n내용: $resBody")
                    onResult("에러(${response.code})")
                }
            }
        })
    }
}

/**
 * 음성 요청 처리 결과
 */
sealed class VoiceRequestResult {
    data class Success(
        val requestType: String,      // "recipe_search", "menu_recommend", etc.
        val keyword: String,           // 검색 키워드
        val responseMessage: String    // 사용자에게 보여줄 메시지
    ) : VoiceRequestResult()

    data class Error(
        val message: String
    ) : VoiceRequestResult()
}