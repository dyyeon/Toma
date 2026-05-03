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
    private val MODEL = "gpt-4o-realtime-preview"

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
                    당신은 요리 보조 앱 '토마'의 비서입니다. 
                    사용자의 한국어 명령을 분석하여 반드시 다음 JSON 형식으로만 응답하세요:
                    { "intent": "NEXT_STEP" | "PREVIOUS_STEP" | "REPEAT_STEP" | "SET_TIMER" | "INGREDIENT_CHECK" | "RECIPE_SEARCH" | "HELP" | "CANCEL", "arguments": { "duration_min": 3, "keyword": "김치찌개" } }
                    - '다음', '넘어가', '다음 단계' -> NEXT_STEP
                    - '이전', '뒤로', '전 단계' -> PREVIOUS_STEP
                    - '다시', '한번 더', '다시 읽어줘' -> REPEAT_STEP
                    - 'N분 타이머', 'N분 맞춰줘' -> SET_TIMER (arguments에 duration_min 포함)
                    - '재료 뭐 있어?', '재료 알려줘' -> INGREDIENT_CHECK
                    - '~ 레시피 찾아줘', '~ 알려줘', '~ 어떻게 만들어?' -> RECIPE_SEARCH (arguments에 keyword 포함)
                    - '도움말', '어떻게 써?' -> HELP
                    - '취소', '그만' -> CANCEL
                """.trimIndent())
                put("input_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply { put("model", "whisper-1") })
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
                Log.d(TAG, "🤖 AI Response: $content")
                onResult(content)
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
