package com.capstone.toma

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null

    // 녹음 시작 함수
    fun startRecording() {
        // 내부 저장소의 캐시 폴더에 임시 파일 생성
        audioFile = File(context.cacheDir, "toma_command.m4a")

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioFile?.absolutePath)

            try {
                prepare()
                start()
                Log.d("AudioRecorder", "녹음 중...")
            } catch (e: IOException) {
                Log.e("AudioRecorder", "녹음기 준비 실패: ${e.message}")
            }
        }
    }

    // 녹음 중지 및 생성된 파일 반환
    fun stopRecording(): File? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            Log.d("AudioRecorder", "녹음 완료: ${audioFile?.absolutePath}")
            audioFile
        } catch (e: Exception) {
            Log.e("AudioRecorder", "녹음 중지 에러: ${e.message}")
            null
        }
    }
}