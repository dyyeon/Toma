package com.capstone.toma

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CHANGED: openWakeWord migration - OpenAI Realtime API Manager
 */
class OpenAiRealtimeManager(
    private val apiKey: String,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val TAG = "RealtimeVoice"
    private val MODEL = OpenAiConfig.REALTIME_MODEL

    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$MODEL")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ OpenAI Realtime Connected")
                sendSessionUpdate()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ Connection Failure: ${t.message}")
                onError(t.message ?: "Unknown WebSocket Error")
                webSocket.close(1001, null)
                this@OpenAiRealtimeManager.webSocket = null
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closing: $reason")
                this@OpenAiRealtimeManager.webSocket = null
            }
        })
    }

    private fun sendSessionUpdate() {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                val modalitiesArr = JSONArray().apply { put("text") }
                put("modalities", modalitiesArr)
                put("instructions", """
                    당신은 요리 보조 앱 '토마'의 음성 명령 인식기입니다.
                    사용자의 한국어 발화를 분석해 아래 JSON 형식 하나만 출력하세요. 설명 텍스트 없이 JSON만 출력하세요.

                    형식:
                    { "intent": "<INTENT>", "arguments": { "duration_min": <숫자>, "keyword": "<텍스트>" } }

                    인텐트 규칙 (우선순위 순):

                    [SET_TIMER] — 숫자 + '분' 이 들어간 모든 타이머 요청
                      예: "3분 맞춰줘", "5분 타이머 시작해", "10분으로 설정해줘", "어.. 2분 재줘"
                      → arguments.duration_min 에 숫자 값 필수 포함

                    [RECOMMENDED_TIMER] — 구체적 숫자 없이 추천/권장 시간 요청
                      예: "추천 시간으로 맞춰줘", "추천으로", "권장 시간으로 설정해"
                      → arguments 불필요

                    [NEXT_STEP] — 다음 단계로 이동
                      예: "다음", "넘겨줘", "다음 단계", "다음으로 넘어가"

                    [PREVIOUS_STEP] — 이전 단계로 이동
                      예: "이전", "뒤로", "전 단계", "돌아가"

                    [REPEAT_STEP] — 현재 단계 반복
                      예: "다시", "한번 더", "다시 읽어줘", "반복해줘"

                    [INGREDIENT_CHECK] — 재료 확인
                      예: "재료 뭐 있어?", "재료 알려줘"

                    [RECIPE_SEARCH] — 레시피 검색 (arguments.keyword 필수)
                      예: "김치찌개 레시피", "파스타 어떻게 만들어?"

                    [CANCEL] — 취소
                      예: "취소", "그만"

                    [UNKNOWN] — 위 어떤 것도 해당 없을 때

                    주의: 발화에 '어..', '음..', '그니까' 같은 망설임이 포함돼도 핵심 키워드로 판단하세요.
                    항상 JSON만 출력하고 다른 텍스트는 절대 포함하지 마세요.
                """.trimIndent())
                put("input_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply { put("model", OpenAiConfig.STT_MODEL) })
                put("turn_detection", JSONObject().apply { 
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                })
            })
        }
        webSocket?.send(sessionUpdate.toString())
    }

    fun sendAudio(pcmData: ByteArray) {
        val audioEvent = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.encodeToString(pcmData, Base64.NO_WRAP))
        }
        webSocket?.send(audioEvent.toString())
    }

    private fun handleMessage(text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "response.audio_transcription.done" -> {
                val transcript = json.optString("transcript")
                Log.d(TAG, "🗣️ User Said: $transcript")
            }
            "response.text.done" -> {
                val content = json.optString("text")
                if (!content.isNullOrBlank()) {
                    Log.d(TAG, "🤖 AI Response: $content")
                    onResult(content)
                }
            }
            "input_audio_buffer.speech_started" -> {
                Log.d(TAG, "🎙️ Speech Started")
            }
            "input_audio_buffer.speech_stopped" -> {
                Log.d(TAG, "🔇 Speech Stopped - Requesting Response")
                // Force response generation once speech stops
                webSocket?.send(JSONObject().apply {
                    put("type", "response.create")
                    put("response", JSONObject().apply {
                        put("instructions", "Analyze the user's last speech and return the JSON intent.")
                    })
                }.toString())
            }
            "response.done" -> {
                // OpenAI Realtime API can return text in several event formats.
                // We check for 'response.done' as a final catch-all.
                val response = json.optJSONObject("response")
                val output = response?.optJSONArray("output")
                output?.let {
                    for (i in 0 until it.length()) {
                        val item = it.optJSONObject(i)
                        // If it's a text message output
                        if (item?.optString("type") == "message") {
                            val contentArray = item.optJSONArray("content")
                            if (contentArray != null) {
                                for (j in 0 until contentArray.length()) {
                                    val contentObj = contentArray.optJSONObject(j)
                                    if (contentObj?.optString("type") == "text") {
                                        val textValue = contentObj.optString("text")
                                        if (textValue.isNotBlank()) {
                                            Log.d(TAG, "🤖 AI Response (deep): $textValue")
                                            onResult(textValue)
                                            return@let // Found it, stop searching
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "error" -> {
                Log.e(TAG, "🚨 OpenAI Error: $text")
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal Closure")
        webSocket = null
    }
}
