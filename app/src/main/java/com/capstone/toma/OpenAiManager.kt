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
    // 만약 여기서 계속 에러가 난다면 BuildConfig.OPENAI_API_KEY 대신
    // "본인의_실제_키"를 직접 넣어 테스트해보세요. (임시 방편)
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
                Log.e("OpenAiManager", "STT 네트워크 실패: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string()
                if (response.isSuccessful && resBody != null) {
                    val text = JSONObject(resBody).optString("text")
                    onResult(text)
                } else {
                    Log.e("OpenAiManager", "STT 응답 에러: ${response.code}")
                    onResult(null)
                }
            }
        })
    }

    /**
     * [2단계: LLM] 텍스트 의도 분석 (gpt-4.1-nano)
     */
    fun analyzeIntent(userText: String, onResult: (String?) -> Unit) {
        val json = JSONObject().apply {
            put("model", "gpt-4.1-nano")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "사용자의 요리 명령을 분석해서 '다음', '이전', '타이머', '재료확인', '알수없음' 중 하나로만 대답해.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
        }

        val requestBody = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
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
                    val content = JSONObject(resBody).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content")
                    onResult(content)
                } else {
                    onResult(null)
                }
            }
        })
    }
}