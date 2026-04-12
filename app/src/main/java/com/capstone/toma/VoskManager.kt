package com.capstone.toma

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskManager(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit // "토마야" 감지 시 실행할 동작
) : RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    // 1. 모델 초기화 (앱 켤 때 한 번만 실행)
    fun initModel() {
        Log.d("VoskManager", "모델 로딩 시작...")

        // assets 폴더에 있는 "model-ko" 압축을 풀고 모델을 준비합니다.
        StorageService.unpack(context, "model-ko", "model",
            { loadedModel: Model -> // 💡 여기가 수정되었습니다! (경로가 아니라 Model 자체를 받음)
                model = loadedModel
                Log.d("VoskManager", "✅ 보스크 모델 로드 완료!")
                startListening() // 로드 성공하면 바로 마이크 켜기
            },
            { exception: IOException -> // 💡 에러 타입도 명확하게 적어줍니다.
                Log.e("VoskManager", "🚨 모델 로드 실패: ${exception.message}")
            }
        )
    }

    // 2. 호출어 귀 기울이기 시작
    fun startListening() {
        if (model == null) {
            Log.e("VoskManager", "모델이 없어서 듣기를 시작할 수 없습니다.")
            return
        }

        try {
            // 16000.0f는 음성 인식 표준 주파수(Sample Rate)입니다.
            val recognizer = Recognizer(model!!, 16000.0f) // 💡 지민님이 수정하신 부분 완벽합니다!
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            Log.d("VoskManager", "🎤 호출어 감지 시작... '토마야' 라고 말해보세요!")
        } catch (e: IOException) {
            Log.e("VoskManager", "서비스 시작 에러: ${e.message}")
        }
    }

    // 3. 듣기 중지 (OpenAI가 작동할 때는 보스크를 꺼둬야 마이크 충돌이 안 납니다)
    fun stopListening() {
        speechService?.stop()
        speechService = null
        Log.d("VoskManager", "🔇 보스크 듣기 중지됨")
    }

    // --- RecognitionListener 필수 오버라이드 함수들 ---

    // 문장 인식이 끝났을 때 결과를 알려줍니다 (JSON 형태)
    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            // 발음이 조금 뭉개질 수 있으니 비슷한 단어들도 잡아냅니다.
            if (it.contains("토마") || it.contains("도마") || it.contains("꼬마")) {
                Log.d("VoskManager", "🚨 [호출어 감지 성공!] 🚨")
                stopListening()      // 내가 대답할 차례니까 마이크 잠시 끄기
                onWakeWordDetected() // 메인 화면으로 신호 보내기!
            }
        }
    }

    // 말하는 도중에 실시간으로 인식되는 부분 (여기선 딱히 안 씁니다)
    override fun onPartialResult(hypothesis: String?) {}

    // 최종 결과
    override fun onFinalResult(hypothesis: String?) {}

    // 에러 발생 시
    override fun onError(exception: Exception?) {
        Log.e("VoskManager", "인식 에러: ${exception?.message}")
    }

    override fun onTimeout() {}
}