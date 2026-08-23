package com.itdeti.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {

    companion object {

        private const val CHANNEL_ID = "itdeti_schedule_reminders"

        private const val CHANNEL_NAME =
            "Напоминания о событиях"

        private const val NOTIFICATION_ID_BASE = 5000
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val itemId =
            intent.getStringExtra("item_id") ?: return

        val itemType =
            intent.getStringExtra("item_type") ?: "event"

        val title =
            intent.getStringExtra("title")
                ?: "Событие"

        val studentName =
            intent.getStringExtra("student_name")

        val startTime =
            intent.getLongExtra("start_time", 0L)

        val lessonKind =
            intent.getStringExtra("lesson_kind")

        createNotificationChannel(context)

        val notificationTitle =
            if (itemType == "lesson") {

                when (lessonKind) {
                    "masterclass" -> "Мастер-класс"
                    else -> "Урок"
                }

            } else {

                "Напоминание"
            }

        val timeText =
            if (startTime > 0) {

                SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
                ).format(Date(startTime))

            } else {
                ""
            }

        val body = buildString {

            append(title)

            if (!studentName.isNullOrBlank()) {

                append(" — ")
                append(studentName)
            }

            if (timeText.isNotBlank()) {

                append("\nНачало в ")
                append(timeText)
            }
        }

        val openAppIntent = Intent(
            context,
            MainActivity::class.java
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            9000,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val notification =
            NotificationCompat.Builder(
                context,
                CHANNEL_ID
            )
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(
                    "⏰ Через 30 минут"
                )
                .setContentText(body)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(body)
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setCategory(
                    NotificationCompat.CATEGORY_REMINDER
                )
                .setVibrate(
                    longArrayOf(0, 250, 150, 250)
                )
                .build()

        val notificationManager =
            context.getSystemService(
                NotificationManager::class.java
            )

        notificationManager.notify(
            NOTIFICATION_ID_BASE +
                    createNotificationId(itemType, itemId),
            notification
        )
    }

    private fun createNotificationChannel(
        context: Context
    ) {

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {

            description =
                "Напоминания о предстоящих уроках и личных событиях"

            enableVibration(true)
        }

        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(channel)
    }

    private fun createNotificationId(
        itemType: String,
        itemId: String
    ): Int {

        return "$itemType:$itemId"
            .hashCode()
            .and(0x7fffffff)
            .mod(100000)
    }
}