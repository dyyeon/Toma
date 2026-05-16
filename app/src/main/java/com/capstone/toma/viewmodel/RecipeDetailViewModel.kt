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

    /**
     * Called when step changes — resets timer for new step.
     * @param seconds Initial duration in seconds.
     */
    fun initStep(seconds: Int) {
        timerJob?.cancel()
        fullDuration = seconds
        _timerRemainingSeconds.value = seconds
        _timerState.value = StepTimerState.IDLE
        _showTimer.value = seconds > 0
    }

    fun startTimer() {
        if (_timerState.value == StepTimerState.RUNNING) return
        
        timerJob?.cancel()
        _timerState.value = StepTimerState.RUNNING
        
        timerJob = viewModelScope.launch {
            while (_timerRemainingSeconds.value > 0) {
                delay(1000)
                _timerRemainingSeconds.value -= 1
            }
            _timerState.value = StepTimerState.FINISHED
        }
    }

    fun pauseTimer() {
        timerJob?.cancel()
        _timerState.value = StepTimerState.PAUSED
    }

    fun resumeTimer() {
        if (_timerState.value != StepTimerState.PAUSED) return
        
        timerJob?.cancel()
        _timerState.value = StepTimerState.RUNNING
        
        timerJob = viewModelScope.launch {
            while (_timerRemainingSeconds.value > 0) {
                delay(1000)
                _timerRemainingSeconds.value -= 1
            }
            _timerState.value = StepTimerState.FINISHED
        }
    }

    fun restartTimer() {
        timerJob?.cancel()
        _timerRemainingSeconds.value = fullDuration
        startTimer()
    }

    fun cancelTimer() {
        timerJob?.cancel()
        _timerState.value = StepTimerState.IDLE
        _showTimer.value = false
    }

    fun adjustDuration(deltaSeconds: Int) {
        val current = _timerRemainingSeconds.value
        val newVal = (current + deltaSeconds).coerceIn(30, 3600)
        _timerRemainingSeconds.value = newVal
        // If the timer hasn't started yet, we also update the full duration so "Restart" uses this adjusted time.
        if (_timerState.value == StepTimerState.IDLE) {
            fullDuration = newVal
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
