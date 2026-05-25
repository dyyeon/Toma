package com.capstone.toma

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiRealtimeManager(
    private val apiKey: String,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onSessionReady: (() -> Unit)? = null // ADDED: session ready callback
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var isConnected = false
    @Volatile private var isSessionReady = false
    private val pendingAudioFrames = ArrayDeque<ByteArray>()
    private var speechStartedAtMs: Long = 0L
    private val MIN_SPEECH_MS = 300L
    private val TAG = "RealtimeVoice"
    private val MODEL = OpenAiConfig.REALTIME_MODEL

    val connected: Boolean get() = isConnected

    fun connect() {
        if (webSocket != null) return

        // gpt-realtime-2 is a production (non-preview) release — the OpenAI-Beta header
        // is only required for preview models and causes a 400 on released endpoints.
        val requestBuilder = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${MODEL}")
            .header("Authorization", "Bearer $apiKey")
        if (MODEL.contains("preview")) {
            requestBuilder.header("OpenAI-Beta", "realtime=v1")
        }

        webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d(TAG, "✅ OpenAI Realtime Connected (model=$MODEL)")
                sendSessionUpdate()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "❌ Connection Failure (model=$MODEL): ${t.message}")
                onError(t.message ?: "Unknown WebSocket Error")
                webSocket?.close(1001, null)
                this@OpenAiRealtimeManager.webSocket = null
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d(TAG, "WebSocket Closing: $reason")
                this@OpenAiRealtimeManager.webSocket = null
            }
        })
    }

    private fun sendSessionUpdate() {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                // REQUIRED: OpenAI requires 'type' to distinguish the session pipeline.
                put("type", "realtime")

                // gpt-realtime-2: text-only output (we parse intent JSON, no TTS audio needed)
                put("output_modalities", org.json.JSONArray().apply { put("text") })

                // gpt-realtime-2: audio config (input format, transcription, VAD) is nested
                // under session.audio.input (not at session root like the old preview model).
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().apply {
                            put("type", "audio/pcm")
                            // Must match AudioStreamManager.SAMPLE_RATE — that's what we actually capture and send.
                            put("rate", 16000)
                        })
                        put("transcription", JSONObject().apply {
                            put("model", OpenAiConfig.STT_MODEL)
                        })
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("prefix_padding_ms", 500)
                            put("silence_duration_ms", 1000)
                            put("create_response", true)
                        })
                    })
                })

                put("instructions", """
                    당신은 요리 보조 앱 '토마'의 음성 명령 인식기입니다.
                    사용자의 한국어 발화를 분석해 아래 JSON 형식 하나만 출력하세요.

                    출력 형식 (한 줄, 순수 JSON만):
                    {"intent":"<INTENT>","arguments":{"duration_min":<숫자>,"keyword":"<텍스트>"}}

                    ══ 인텐트 규칙 (우선순위 순) ══

                    [SET_TIMER] — 숫자 + '분' 이 들어간 타이머 요청
                      예: "3분 맞춰줘", "5분 타이머", "10분으로 설정"
                      → arguments.duration_min 에 숫자 필수

                    [RECOMMENDED_TIMER] — 숫자 없이 추천/권장 시간 요청
                      예: "추천 시간으로", "권장 시간으로 맞춰줘"

                    [NEXT_STEP] — 다음 단계
                      예: "다음", "넘겨", "다음 단계", "다음으로"
                      ※ 한 글자/한 단어 명령도 유효함

                    [PREVIOUS_STEP] — 이전 단계
                      예: "이전", "뒤로", "전 단계", "돌아가"
                      ※ 한 글자/한 단어 명령도 유효함

                    [REPEAT_STEP] — 현재 단계 반복
                      예: "다시", "한번 더", "반복"

                    [INGREDIENT_CHECK] — 재료 확인
                      예: "재료 뭐야", "재료 알려줘"

                    [RECIPE_SEARCH] — 레시피 검색 (arguments.keyword 필수)
                      예: "김치찌개 레시피", "파스타 어떻게 만들어"
                      ※ keyword가 없으면 UNKNOWN 반환

                    [CANCEL] — 취소/중단
                      예: "취소", "그만", "멈춰", "중지", "스톱"
                      ※ 한 단어 명령도 유효함

                    [UNKNOWN] — 위 어떤 것도 해당 없을 때

                    ══ 단어 명령 처리 규칙 ══
                    한 단어/한 음절 명령도 항상 유효한 인텐트로 분류하세요.
                    "다음" 단독 -> NEXT_STEP
                    "이전" 단독 -> PREVIOUS_STEP
                    "취소" 단독 -> CANCEL
                    "멈춰" 단독 -> CANCEL
                    "중지" 단독 -> CANCEL
                    "다시" 단독 -> REPEAT_STEP
                    "재료" 단독 -> INGREDIENT_CHECK
                    "검색" 단독 (키워드 없음) -> UNKNOWN

                    ══ 음식 카테고리 분류 규칙 ══
                    RECIPE_SEARCH 시 keyword의 카테고리를 문화적 원산지 기준으로 판단하세요.
                    한국에서 많이 먹는다는 이유만으로 한식으로 분류하지 마세요.

                    한식: 김치찌개, 된장찌개, 비빔밥, 삼겹살, 떡볶이, 김밥, 순두부찌개, 갈비, 불고기, 잡채, 해물파전
                    중식: 짜장면, 짬뽕, 마라탕, 마라샹궈, 탕수육, 깐풍기, 양장피, 훠궈, 딤섬, 팔보채, 유린기
                    일식: 초밥, 사시미, 라멘, 우동, 소바, 돈카츠, 오코노미야키, 타코야키, 텐동, 규동
                    양식: 파스타, 피자, 스테이크, 리조또, 햄버거, 샌드위치, 크림스프, 오믈렛, 그라탱
                    동남아식: 팟타이, 쌀국수, 나시고렝, 반미, 똠양꿍

                    절대 규칙:
                    - 마라탕 -> 항상 중식
                    - 짜장면 -> 항상 중식
                    - 초밥 -> 항상 일식
                    - 피자 -> 항상 양식

                    ══ 출력 규칙 ══
                    반드시 순수 JSON 한 줄만 출력하세요.
                    마크다운(```), 설명 텍스트, 줄바꿈 절대 금지.
                    JSON 외 어떤 텍스트도 포함하지 마세요.
                """.trimIndent())
            })
        }
        Log.d(TAG, "📤 Sending minimal session.update with type=realtime")
        webSocket?.send(sessionUpdate.toString())
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected) return
        if (!isSessionReady) {
            pendingAudioFrames.addLast(pcmData)
            return
        }
        sendAudioFrame(pcmData)
    }

    private fun sendAudioFrame(pcmData: ByteArray) {
        val audioEvent = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.encodeToString(pcmData, Base64.NO_WRAP))
        }
        webSocket?.send(audioEvent.toString())
    }

    private fun flushPendingAudio() {
        val count = pendingAudioFrames.size
        while (pendingAudioFrames.isNotEmpty()) {
            sendAudioFrame(pendingAudioFrames.removeFirst())
        }
        if (count > 0) {
            Log.d(TAG, "📦 Flushed $count buffered audio frames")
            // Ensure the flushed audio is processed immediately
            webSocket?.send(JSONObject().apply { put("type", "input_audio_buffer.commit") }.toString())
        }
    }

    private fun handleMessage(text: String) {
        val json = JSONObject(text)
        val type = json.optString("type")
        
        when (type) {
            "session.updated" -> {
                Log.d(TAG, "✅ Session updated & acknowledged — unblocking audio")
                isSessionReady = true
                flushPendingAudio()
                onSessionReady?.invoke() // ADDED: fire callback
            }
            "response.audio_transcription.done" -> {
                val transcript = json.optString("transcript")
                Log.d(TAG, "🗣️ User Said: $transcript")
            }
            "response.text.done", "response.output_text.done" -> {
                val raw = json.optString("text")
                if (!raw.isNullOrBlank()) {
                    val content = extractJson(raw)
                    Log.d(TAG, "🤖 AI Response: $content")
                    onResult(content)
                }
            }
            "input_audio_buffer.speech_started" -> {
                speechStartedAtMs = System.currentTimeMillis()
                Log.d(TAG, "🎙️ Speech Started")
            }
            "input_audio_buffer.speech_stopped" -> {
                val durationMs = System.currentTimeMillis() - speechStartedAtMs
                if (durationMs < MIN_SPEECH_MS) {
                    webSocket?.send(JSONObject().apply { put("type", "input_audio_buffer.clear") }.toString())
                    Log.d(TAG, "🔇 Speech too short (${durationMs}ms) — discarded")
                } else {
                    Log.d(TAG, "🔇 Speech Stopped (${durationMs}ms) — server_vad will auto-create response")
                }
            }
            "response.created" -> {
                Log.d(TAG, "⏳ response.created - AI is processing...")
            }
            "response.done" -> {
                Log.d(TAG, "✅ response.done")
            }
            "error" -> {
                Log.e(TAG, "🚨 OpenAI Error: $text")
            }
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) text.substring(start, end + 1) else text.trim()
    }

    fun disconnect() {
        isConnected = false
        isSessionReady = false
        speechStartedAtMs = 0L
        pendingAudioFrames.clear()
        webSocket?.close(1000, "Normal Closure")
        webSocket = null
    }
}
