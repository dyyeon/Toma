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
 * CHANGED: openWakeWord migration - Low-latency Audio Capture Hub
 */
class AudioStreamManager(private val context: Context) {
    private val TAG = "AudioStream"
    private val SAMPLE_RATE = 24000 // CHANGED: 24kHz for OpenAI Realtime API compatibility
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = 1280 // Standard chunk for pipeline processing

    private var audioRecord: AudioRecord? = null
    private var isRunning = false

    // FIX #1 (v1.0 bug): CONFLATED silently discards every frame that arrives while
    // the consumer is busy. A single dropped frame breaks the temporal context window
    // and resets consecutiveDetections to 0, making the wake-word impossible to
    // recognise consistently. Use a bounded buffer instead; trySend() still drops
    // gracefully under extreme load but keeps the last N frames, not just 1.
    val pcmChannel = Channel<ByteArray>(128)

    private var onEnrollmentData: ((ByteArray) -> Unit)? = null

    // --- Audio Focus ---
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
                    // AudioRecord re-creates itself via the self-healing path if interrupted;
                    // no additional action needed here.
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

    // FIX #2: Extracted so the self-healing path can recreate AudioRecord without
    // re-entering startCapture() and spinning up a second thread.
    @SuppressLint("MissingPermission")
    private fun createAndStartAudioRecord(): AudioRecord {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // FIX #3: BUFFER_SIZE * 2 (2560 B) was often smaller than minBufSize (~3200 B on most
        // devices). Use 8× the read chunk size so the kernel ring buffer never overruns even
        // if the read thread is briefly preempted.
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
            minBufSize.coerceAtLeast(BUFFER_SIZE * 8)
        )
        if (NoiseSuppressor.isAvailable())     NoiseSuppressor.create(ar.audioSessionId)?.enabled = true
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.enabled = true
        ar.startRecording()
        return ar
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isRunning) return
        isRunning = true
        
        // Request audio focus with exclusive gain to prevent hardware AEC interference during playback
        requestAudioFocus()
        audioRecord = createAndStartAudioRecord()

        thread(name = "TomaAudioThread") {
            val buffer = ByteArray(BUFFER_SIZE * 2) // 16-bit PCM = 2 bytes/sample
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                when {
                    read > 0 -> {
                        val data = buffer.copyOfRange(0, read)
                        pcmChannel.trySend(data)
                        onEnrollmentData?.invoke(data)
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        // FIX #4 (self-healing): AudioRecord enters an error state after audio-focus
                        // loss (phone call, another app grabbing the mic, etc.). Without recovery the
                        // thread loops forever returning errors and the wake-word stops working until
                        // the app is restarted. Tear down and recreate the AudioRecord in-place.
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
                    // read == 0 → no data yet, loop immediately
                }
            }
        }
    }

    fun stopCapture() {
        if (!isRunning) return
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        releaseAudioFocus()
    }
}
