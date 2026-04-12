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

    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!audioFile.exists()) {
            Log.e("OpenAiManager", "파일 없음: ${audioFile.absolutePath}")
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mpeg".toMediaType()))
            .addFormDataPart("model", "gpt-4o-mini-transcribe")
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
                    onResult(JSONObject(resBody).optString("text"))
                } else {
                    Log.e("OpenAiManager", "STT 응답 에러: ${response.code} / $resBody")
                    onResult(null)
                }
            }
        })
    }

    /**
     * [2단계: LLM] 유연한 프롬프트 + 강력한 에러 로깅
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
                    // 🚨 핵심: 401이 뜨면 여기에 이유가 상세히 적혀서 나옵니다.
                    Log.e("OpenAiManager", "🚨 LLM 응답 에러 (코드: ${response.code}) 🚨\n내용: $resBody")
                    onResult("에러(${response.code})")
                }
            }
        })
    }
}