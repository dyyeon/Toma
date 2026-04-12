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

    // 네트워크 통신 설정
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // BuildConfig에서 API 키 가져오기
    private val apiKey = BuildConfig.OPENAI_API_KEY

    /**
     * [1단계: STT] 음성을 텍스트로 (gpt-4o-mini-transcribe)
     */
    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!audioFile.exists()) {
            Log.e("OpenAiManager", "파일이 존재하지 않습니다: ${audioFile.absolutePath}")
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mpeg".toMediaType()))
            // STT 모델은 보통 whisper-1 을 사용합니다. (gpt-4o-mini-transcribe 지원 여부에 따라 whisper-1로 변경 가능)
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
                Log.e("OpenAiManager", "STT 네트워크 실패: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    val text = JSONObject(resBody).optString("text")
                    onResult(text)
                } else {
                    Log.e("OpenAiManager", "STT 응답 에러: ${response.code} / $resBody")
                    onResult(null)
                }
            }
        })
    }

    /**
     * [2단계: LLM] 텍스트 의도 분석
     */
    fun analyzeIntent(userText: String, onResult: (String?) -> Unit) {
        // 💡 고도화된 시스템 프롬프트 (정확도 99% 목표)
        val systemPrompt = """
            당신은 스마트 요리 보조 AI '토마(Toma)'입니다.
            사용자의 음성 인식 텍스트를 분석하여 아래 5가지 의도 중 하나로만 대답하세요.
            다른 인사말이나 설명은 절대 붙이지 마세요.

            1. 다음: 다음 요리 단계로 넘어갈 때 (예: "다음", "그다음은", "넘어가자", "다 했어")
            2. 이전: 이전 단계로 가거나 다시 듣고 싶을 때 (예: "방금 뭐라고?", "이전", "다시 말해줘", "놓쳤어")
            3. 타이머: 시간 측정이 필요할 때 (예: "3분 타이머", "타이머 켜줘", "시간 재줘")
            4. 재료확인: 요리 재료가 궁금할 때 (예: "재료 뭐뭐 들어가?", "재료 확인", "뭐 필요해")
            5. 알수없음: 위 4가지에 해당하지 않는 모든 일상적인 대화, 잡음, 혹은 의미 없는 단어

            출력 형식: 오직 '다음', '이전', '타이머', '재료확인', '알수없음' 중 하나의 단어만 출력.
        """.trimIndent()

        val json = JSONObject().apply {
            put("model", "gpt-4o-mini") // 실사용 가능한 모델명으로 수정 (빠르고 저렴함)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
            // 💡 temperature를 0.0으로 설정하면 AI가 창의력을 발휘하지 않고 딱 정해진 답변만 합니다.
            put("temperature", 0.0)
        }

        val requestBody = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("OpenAiManager", "LLM 요청 실패: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    val content = JSONObject(resBody).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content").trim()
                    onResult(content)
                } else {
                    Log.e("OpenAiManager", "LLM 응답 에러: ${response.code} / $resBody")
                    onResult(null)
                }
            }
        })
    }
}