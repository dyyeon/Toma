package com.capstone.toma

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskManager(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) : RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    // 🔧 신뢰도 임계값 조정 (0.0 ~ 1.0)
    // - 0.7: 어느 정도 정확함 (권장)
    // - 0.5: 더 자주 반응하지만 오인식 증가
    private val CONFIDENCE_THRESHOLD = 0.6f

    // 재시도 횟수 제한
    private var initRetryCount = 0
    private val MAX_INIT_RETRIES = 3

    // =====================================================
    // 1. 모델 초기화 (앱 켤 때 한 번만 실행)
    // =====================================================
    fun initModel() {
        Log.d("VoskManager", "🔄 모델 로딩 시작... (재시도: $initRetryCount/$MAX_INIT_RETRIES)")

        if (initRetryCount >= MAX_INIT_RETRIES) {
            Log.e("VoskManager", "❌ 모델 초기화 최대 재시도 횟수 초과!")
            return
        }

        StorageService.unpack(
            context,
            "model-ko",
            "model",
            { loadedModel: Model ->
                model = loadedModel
                initRetryCount = 0  // 성공하면 재시도 카운트 리셋
                Log.d("VoskManager", "✅ 보스크 모델 로드 완료!")
                startListening()
            },
            { exception: IOException ->
                Log.e("VoskManager", "🚨 모델 로드 실패: ${exception.message}")
                initRetryCount++
                // 3초 후 자동 재시도
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (initRetryCount < MAX_INIT_RETRIES) {
                        initModel()
                    }
                }, 3000)
            }
        )
    }

    // =====================================================
    // 2. 호출어 감지 시작
    // =====================================================
    fun startListening() {
        if (model == null) {
            Log.w("VoskManager", "⚠️ 모델이 없음. 초기화 다시 시작")
            initModel()
            return
        }

        try {
            val recognizer = Recognizer(model!!, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            Log.d("VoskManager", "🎤 호출어 감지 시작... '토마야' 라고 말해보세요!")
        } catch (e: IOException) {
            Log.e("VoskManager", "❌ 서비스 시작 에러: ${e.message}")
        }
    }

    // =====================================================
    // 3. 호출어 감지 중지
    // =====================================================
    fun stopListening() {
        try {
            speechService?.stop()
            speechService = null
            Log.d("VoskManager", "🔇 호출어 감지 중지됨")
        } catch (e: Exception) {
            Log.e("VoskManager", "중지 중 에러: ${e.message}")
        }
    }

    // =====================================================
    // ⭐ 핵심 변경: JSON 파싱 + 신뢰도 검증
    // =====================================================
    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            Log.d("VoskManager", "📝 원본 응답: $it")

            try {
                // ✅ 방법 1: JSON 파싱으로 신뢰도와 텍스트 추출
                val json = JSONObject(it)

                // Vosk의 응답 형식:
                // {"result":[{"conf":0.95,"result":"토마야"}],"text":"토마야"}
                val resultArray = json.optJSONArray("result")

                if (resultArray != null && resultArray.length() > 0) {
                    val firstResult = resultArray.getJSONObject(0)
                    val confidence = firstResult.optDouble("conf", 0.0).toFloat()
                    val recognizedText = firstResult.optString("result", "").lowercase()

                    Log.d("VoskManager", "📊 파싱 성공 | 텍스트: '$recognizedText' | 신뢰도: $confidence (${(confidence*100).toInt()}%)")

                    // ✅ 신뢰도 필터링: 임계값 이상인 경우만 처리
                    if (confidence >= CONFIDENCE_THRESHOLD) {
                        checkWakeWord(recognizedText, confidence)
                    } else {
                        Log.d("VoskManager", "⚠️ 신뢰도 낮음 ($confidence < $CONFIDENCE_THRESHOLD) - 무시")
                    }
                } else {
                    // 결과 배열이 비어있으면 "text" 필드에서 추출 (대체 방법)
                    val fallbackText = json.optString("text", "").lowercase()
                    if (fallbackText.isNotEmpty()) {
                        Log.d("VoskManager", "📝 폴백 추출: '$fallbackText'")
                        checkWakeWord(fallbackText, 0.5f)  // 낮은 신뢰도로 처리
                    }
                }
            } catch (e: Exception) {
                Log.w("VoskManager", "❌ JSON 파싱 실패: ${e.message}")

                // ✅ 방법 2: JSON 파싱 실패 시 단순 문자열 검색 (폴백)
                Log.d("VoskManager", "🔄 폴백: 문자열 검색 시도")
                if (it.contains("토마") || it.contains("도마")) {
                    Log.d("VoskManager", "✅ [폴백 감지] 호출어 발견!")
                    stopListening()
                    onWakeWordDetected()
                }
            }
        }
    }

    // =====================================================
    // ⭐ 호출어 판별 함수 (분리됨)
    // =====================================================
    private fun checkWakeWord(text: String, confidence: Float) {
        val wakeWordDetected = when {
            // 정확한 매칭: "토마야" 전체 포함
            text.contains("토마야") -> true

            // 부분 매칭: "토마"만 있어도 인정 (음절 손실 대비)
            text.contains("토마") -> true

            // 발음 유사: "도마" (유사음)
            text.contains("도마") -> true

            // 마지막 수단: 짧은 문자열 + "마" 포함
            text.length <= 3 && text.contains("마") -> {
                Log.d("VoskManager", "⚠️ 짧은 문자열로 감지: '$text'")
                true
            }

            else -> false
        }

        if (wakeWordDetected) {
            Log.d("VoskManager", "🚨 [호출어 감지 성공!] '$text' (신뢰도: ${(confidence*100).toInt()}%)")
            stopListening()
            onWakeWordDetected()
        } else {
            Log.d("VoskManager", "❌ 호출어 아님: '$text'")
        }
    }

    // =====================================================
    // 부분 결과 (실시간 피드백)
    // =====================================================
    override fun onPartialResult(hypothesis: String?) {
        hypothesis?.let {
            try {
                val json = JSONObject(it)
                val partial = json.optString("partial", "").lowercase()

                if (partial.isNotEmpty()) {
                    Log.d("VoskManager", "🎧 말하는 중... '$partial'")
                }
            } catch (e: Exception) {
                Log.d("VoskManager", "🎧 말하는 중... $it")
            }
        }
    }

    // =====================================================
    // 최종 결과 (필요시 사용)
    // =====================================================
    override fun onFinalResult(hypothesis: String?) {
        // onResult()에서 이미 처리되므로 여기서는 비움
        Log.d("VoskManager", "🏁 최종 결과 수신")
    }

    // =====================================================
    // 에러 처리
    // =====================================================
    override fun onError(exception: Exception?) {
        Log.e("VoskManager", "❌ 인식 에러: ${exception?.message}")
        exception?.printStackTrace()
    }

    // =====================================================
    // 타임아웃 처리 (자동 재시작)
    // =====================================================
    override fun onTimeout() {
        Log.d("VoskManager", "⏱️ 타임아웃 발생 - 자동 재시작")
        startListening()
    }

    // =====================================================
    // 상태 확인 함수 (필요시 외부에서 호출)
    // =====================================================
    fun isListening(): Boolean {
        return speechService != null && model != null
    }

    // =====================================================
    // 리소스 해제 (앱 종료 시)
    // =====================================================
    fun release() {
        stopListening()
        model = null
        Log.d("VoskManager", "🔌 리소스 해제 완료")
    }
}