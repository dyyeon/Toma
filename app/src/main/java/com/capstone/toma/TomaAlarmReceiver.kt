package com.capstone.toma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * CHANGED: openWakeWord migration - Handles Timer Alarm
 */
class TomaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "toma_timer_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "요리 타이머",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "타이머 종료 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_tomato)
            .setContentTitle("토마 타이머")
            .setContentText("설정한 요리 시간이 다 되었습니다! 🍳")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmSound)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
    }
}
