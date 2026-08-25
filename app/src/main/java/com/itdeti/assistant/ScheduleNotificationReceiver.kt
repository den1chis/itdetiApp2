package com.itdeti.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ScheduleNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "itdeti_schedule"
        private const val CHANNEL_NAME = "Занятия и события"

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Напоминания о занятиях и событиях за 30 минут"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.itdeti.SCHEDULE_REMINDER") return

        createChannel(context)

        val itemType = intent.getStringExtra("item_type") ?: "event"
        val title = intent.getStringExtra("title") ?: "Событие"
        val studentName = intent.getStringExtra("student_name") ?: ""
        val lessonKind = intent.getStringExtra("lesson_kind") ?: ""

        val notificationTitle = if (itemType == "lesson") {
            "Занятие через 30 минут"
        } else {
            "Событие через 30 минут"
        }

        val notificationText = if (itemType == "lesson") {
            buildString {
                append(title)
                if (studentName.isNotBlank()) append(" • $studentName")
                if (lessonKind.isNotBlank()) append(" • $lessonKind")
            }
        } else {
            title
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            4001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val notificationId = stableNotificationId(intent.getStringExtra("item_id") ?: title)
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }

    private fun stableNotificationId(value: String): Int {
        val hash = value.hashCode()
        return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash)
    }
}
