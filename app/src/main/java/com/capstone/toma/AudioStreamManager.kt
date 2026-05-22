package com.capstone.toma

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.thread

/**
 * openWakeWord migration - Low-latency Audio Capture Hub
 */
class AudioStreamManager(private val context: Context) {
    private val TAG = "AudioStream"
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = 1280 // 80ms at 16kHz, 16-bit mono => 2560 bytes/read

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRunning = false

    val pcmChannel = Channel<ByteArray>(128)

    private var onEnrollmentData: ((ByteArray) -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()

            audioFocusRequest = req
            val result = audioManager.requestAudioFocus(req)
            Log.d(TAG, "Audio focus request result: $result")
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun startEnrollmentMode(callback: (ByteArray) -> Unit) {
        onEnrollmentData = callback
    }

    fun stopEnrollmentMode() {
        onEnrollmentData = null
    }

    @SuppressLint("MissingPermission")
    private fun createAndStartAudioRecord(): AudioRecord {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            throw IllegalStateException("Invalid min buffer size: $minBufSize")
        }

        val bufferSize = minBufSize.coerceAtLeast(BUFFER_SIZE * 8)

        fun buildAudioRecord(audioSource: Int): AudioRecord {
            return AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }

        var ar = buildAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "VOICE_RECOGNITION init failed, fallback to MIC")
            ar.release()
            ar = buildAudioRecord(MediaRecorder.AudioSource.MIC)
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException(
                "AudioRecord init failed. sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize"
            )
        }

        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(ar.audioSessionId)?.enabled = true
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(ar.audioSessionId)?.enabled = true
        }

        ar.startRecording()

        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            ar.release()
            throw IllegalStateException("AudioRecord failed to enter RECORDSTATE_RECORDING")
        }

        Log.d(TAG, "AudioRecord started: sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize")
        return ar
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRunning) return

        try {
            requestAudioFocus()
            audioRecord = createAndStartAudioRecord()
            isRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture: ${e.message}")
            releaseAudioFocus()
            isRunning = false
            return
        }

        thread(name = "TomaAudioThread") {
            val buffer = ByteArray(BUFFER_SIZE * 2)

            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                when {
                    read > 0 -> {
                        val data = buffer.copyOfRange(0, read)

                        val result = pcmChannel.trySend(data)
                        if (result.isFailure) {
                            Log.w(TAG, "pcmChannel drop: ${data.size} bytes")
                        }

                        onEnrollmentData?.invoke(data)
                    }

                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.w(TAG, "AudioRecord read error ($read) — attempting self-heal restart")
                        try {
                            audioRecord?.stop()
                            audioRecord?.release()
                            Thread.sleep(300)

                            if (isRunning) {
                                audioRecord = createAndStartAudioRecord()
                                Log.d(TAG, "AudioRecord restarted successfully")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Self-heal restart failed: ${e.message}")
                        }
                    }

                    read == 0 -> {
                        // no data yet
                    }

                    else -> {
                        Log.w(TAG, "Unexpected AudioRecord read result: $read")
                    }
                }
            }
        }
    }

    fun stopCapture() {
        if (!isRunning) return

        isRunning = false

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }

        audioRecord = null
        releaseAudioFocus()
    }
}