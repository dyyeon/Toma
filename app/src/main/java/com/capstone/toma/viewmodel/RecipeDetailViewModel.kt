package com.capstone.toma.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class StepTimerState { IDLE, RUNNING, PAUSED, FINISHED }

class RecipeDetailViewModel : ViewModel() {

    private var fullDuration: Int = 0

    private val _timerState = MutableStateFlow(StepTimerState.IDLE)
    val timerState: StateFlow<StepTimerState> = _timerState

    private val _timerRemainingSeconds = MutableStateFlow(0)
    val timerRemainingSeconds: StateFlow<Int> = _timerRemainingSeconds

    private val _showTimer = MutableStateFlow(false)
    val showTimer: StateFlow<Boolean> = _showTimer

    private var timerJob: Job? = null

    /** Called when step changes — resets timer for the new step. */
    fun initStep(seconds: Int) {
        timerJob?.cancel()
        fullDuration = seconds
        _timerRemainingSeconds.value = seconds
        _timerState.value = StepTimerState.IDLE
        _showTimer.value = seconds > 0
    }

    /** Reopens the timer card after user dismissed it via ×. Resets to IDLE with full duration. */
    fun showTimerCard() {
        if (fullDuration > 0) {
            timerJob?.cancel()
            _timerState.value = StepTimerState.IDLE
            _timerRemainingSeconds.value = fullDuration
            _showTimer.value = true
        }
    }

    fun startTimer() {
        if (_timerState.value == StepTimerState.RUNNING) return
        timerJob?.cancel()
        _timerState.value = StepTimerState.RUNNING
        runCountdown()
    }

    fun pauseTimer() {
        if (_timerState.value != StepTimerState.RUNNING) return
        timerJob?.cancel()
        _timerState.value = StepTimerState.PAUSED
    }

    fun resumeTimer() {
        if (_timerState.value != StepTimerState.PAUSED) return
        timerJob?.cancel()
        _timerState.value = StepTimerState.RUNNING
        runCountdown()
    }

    fun restartTimer() {
        timerJob?.cancel()
        _timerRemainingSeconds.value = fullDuration
        _timerState.value = StepTimerState.RUNNING
        runCountdown()
    }

    /** × button: cancel current run and hide the card. State returns to IDLE. */
    fun cancelTimer() {
        timerJob?.cancel()
        _timerState.value = StepTimerState.IDLE
        _timerRemainingSeconds.value = fullDuration
        _showTimer.value = false
    }

    /** Adjusts remaining time (and base duration when IDLE) by [deltaSeconds]. Clamped to [30, 3600]. */
    fun adjustDuration(deltaSeconds: Int) {
        val newVal = (_timerRemainingSeconds.value + deltaSeconds).coerceIn(30, 3600)
        _timerRemainingSeconds.value = newVal
        if (_timerState.value == StepTimerState.IDLE) {
            fullDuration = newVal
        }
    }

    private fun runCountdown() {
        timerJob = viewModelScope.launch {
            while (_timerRemainingSeconds.value > 0) {
                delay(1000L)
                _timerRemainingSeconds.value -= 1
            }
            _timerState.value = StepTimerState.FINISHED
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
