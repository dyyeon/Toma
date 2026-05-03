package com.capstone.toma

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.thread

/**
 * CHANGED: openWakeWord migration - Low-latency Audio Capture Hub
 */
class AudioStreamManager {
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = 1280 // 80ms chunks for openWakeWord

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    
    // Channel for PCM data (Backpressure handled by CONFLATED or BUFFERED)
    val pcmChannel = Channel<ByteArray>(Channel.CONFLATED)
    
    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRunning) return
        
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // Optimized for STT/WakeWord
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufSize.coerceAtLeast(BUFFER_SIZE * 2)
        )

        // Enable Hardware Noise Suppression if available
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioRecord!!.audioSessionId)?.enabled = true
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioRecord!!.audioSessionId)?.enabled = true
        }

        isRunning = true
        audioRecord?.startRecording()

        thread(name = "TomaAudioThread") {
            val buffer = ByteArray(BUFFER_SIZE * 2) // 16-bit PCM (2 bytes per sample)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    pcmChannel.trySend(buffer.copyOfRange(0, read))
                }
            }
        }
    }

    fun stopCapture() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
