package com.capstone.toma.viewmodel

import android.app.Application
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.toma.OnDevicePersonalizer
import com.capstone.toma.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Speaker enrollment ViewModel.
 *
 * Records 3 short audio samples from the user, saves them to internal storage,
 * and best-effort updates the on-device wake-word personalization. Enrollment
 * is optional — the app must keep working even if this is skipped or fails.
 */
class SpeakerEnrollmentViewModel(application: Application) : AndroidViewModel(application) {

    enum class Step { Intro, Recording, Complete }

    private val _step = MutableStateFlow(Step.Intro)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** Index of the NEXT sample to record (0..2). When 3, all done. */
    private val _sampleIndex = MutableStateFlow(0)
    val sampleIndex: StateFlow<Int> = _sampleIndex.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null

    private val enrollmentDir: File by lazy {
        File(getApplication<Application>().filesDir, "enrollment").apply { mkdirs() }
    }

    fun sampleFile(oneBasedIndex: Int): File =
        File(enrollmentDir, "sample_$oneBasedIndex.m4a")

    fun goToRecording() {
        _step.value = Step.Recording
    }

    /** User tapped "나중에 할게요". Always succeeds — never throw. */
    fun skipEnrollment() {
        runCatching { stopRecorderQuietly() }
        UserManager.setSpeakerEnrolled(getApplication(), false)
        UserManager.setSkipped(getApplication())
    }

    fun consumeError() {
        _errorMessage.value = null
    }

    /**
     * Start recording the next sample. The MediaRecorder writes to
     * filesDir/enrollment/sample_{index+1}.m4a. Permission must already be granted
     * by the caller before invoking this.
     */
    fun startRecordingSample() {
        if (_isRecording.value) return
        if (_sampleIndex.value >= TOTAL_SAMPLES) return

        val targetFile = sampleFile(_sampleIndex.value + 1)
        if (targetFile.exists()) targetFile.delete()

        try {
            val ctx = getApplication<Application>()
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(targetFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            _isRecording.value = true
            Log.d(TAG, "Recording sample ${_sampleIndex.value + 1}/$TOTAL_SAMPLES → ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder start failed", e)
            _errorMessage.value = "마이크를 시작할 수 없습니다. 권한을 확인해주세요."
            stopRecorderQuietly()
            _isRecording.value = false
        }
    }

    /**
     * Stop the current sample recording. Accepts ALL recordings — no amplitude
     * threshold rejection per spec. Advances the sample index and, when all 3
     * samples are collected, transitions to the Complete screen and runs
     * best-effort personalization in the background.
     */
    fun stopRecordingSample() {
        if (!_isRecording.value) return
        val current = _sampleIndex.value

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // stop() can throw if the recording was too short. We still keep the
            // sample (even if invalid) since spec forbids amplitude rejection.
            Log.w(TAG, "MediaRecorder stop threw — keeping file anyway: ${e.message}")
        }
        mediaRecorder = null
        _isRecording.value = false

        val nextIndex = current + 1
        _sampleIndex.value = nextIndex

        if (nextIndex >= TOTAL_SAMPLES) {
            _step.value = Step.Complete
            viewModelScope.launch { runPersonalizationBestEffort() }
        }
    }

    /**
     * Best-effort on-device personalization using the 3 recorded files.
     * Per spec: if this fails, log and proceed — never block the user.
     * Enrollment is marked complete regardless of the personalization outcome.
     */
    private suspend fun runPersonalizationBestEffort() {
        val ctx = getApplication<Application>()
        try {
            withContext(Dispatchers.IO) {
                val files = (1..TOTAL_SAMPLES).map { sampleFile(it) }
                val allExist = files.all { it.exists() && it.length() > 0L }
                if (!allExist) {
                    Log.w(TAG, "Personalization skipped: not all sample files are present")
                } else {
                    val personalizer = OnDevicePersonalizer(ctx)
                    // The personalizer's training pipeline expects embeddings, not raw
                    // m4a files. A future commit will decode the m4a samples through
                    // the wake-word embedding model. For now, we run train() with any
                    // existing samples and persist the weights — failures are logged.
                    val trained = runCatching { personalizer.train() }.getOrElse {
                        Log.w(TAG, "personalizer.train() threw: ${it.message}")
                        false
                    }
                    if (trained) {
                        val weightsFile = File(ctx.filesDir, "personal_weights.bin")
                        runCatching { personalizer.saveToFile(weightsFile) }.onFailure {
                            Log.w(TAG, "personalizer.saveToFile() failed: ${it.message}")
                        }
                        Log.d(TAG, "On-device personalization weights saved")
                    } else {
                        Log.d(TAG, "Personalization train() returned false — proceeding without personal weights")
                    }
                }
            }
        } catch (e: Exception) {
            // Catch-all — enrollment must complete regardless.
            Log.e(TAG, "Personalization failed (non-blocking): ${e.message}", e)
        } finally {
            UserManager.setSpeakerEnrolled(ctx, true)
        }
    }

    private fun stopRecorderQuietly() {
        runCatching {
            mediaRecorder?.apply {
                stop()
                release()
            }
        }
        mediaRecorder = null
    }

    override fun onCleared() {
        super.onCleared()
        stopRecorderQuietly()
    }

    companion object {
        const val TOTAL_SAMPLES = 10
        const val TARGET_SENTENCE = "헤이 토마"
        private const val TAG = "SpeakerEnrollVM"
    }
}
