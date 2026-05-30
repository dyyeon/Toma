package com.capstone.toma

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TimerService : Service() {
    private val TAG = "TimerService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    private val binder = TimerBinder()

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val minutes = intent?.getIntOfExtra("minutes", 0) ?: 0
        val seconds = intent?.getIntOfExtra("seconds", 0) ?: 0

        when (action) {
            "START" -> startTimer(minutes * 60 + seconds)
            "STOP" -> stopTimer()
        }
        return START_NOT_STICKY
    }

    private fun startTimer(totalSeconds: Int) {
        if (totalSeconds <= 0) return

        stopTimer() // Clear any existing

        _remainingSeconds.value = totalSeconds
        _isTimerRunning.value = true
        
        startForeground(NOTIFICATION_ID, createNotification(_remainingSeconds.value))
        
        timerJob = serviceScope.launch {
            while (_remainingSeconds.value > 0) {
                delay(1000)
                _remainingSeconds.value -= 1
                updateNotification(_remainingSeconds.value)
            }
            onTimerFinished()
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        _remainingSeconds.value = 0
        _isTimerRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onTimerFinished() {
        _isTimerRunning.value = false
        // Trigger the alarm receiver logic directly or via broadcast
        val alarmIntent = Intent(this, TomaAlarmReceiver::class.java)
        sendBroadcast(alarmIntent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(seconds: Int): Notification {
        val channelId = "toma_timer_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "타이머 서비스", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val minutes = seconds / 60
        val remainingSecs = seconds % 60
        val timeStr = String.format("%02d:%02d", minutes, remainingSecs)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("토마 요리 타이머")
            .setContentText("남은 시간: $timeStr")
            .setSmallIcon(R.drawable.ic_tomato)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(seconds: Int) {
        val notification = createNotification(seconds)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun Intent.getIntOfExtra(key: String, default: Int): Int {
        return if (hasExtra(key)) getIntExtra(key, default) else default
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
