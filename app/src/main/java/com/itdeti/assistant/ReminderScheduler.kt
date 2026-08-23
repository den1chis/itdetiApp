package com.itdeti.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

object ReminderScheduler {

    private const val TAG = "itdeti_Reminders"

    private const val PREFS = "itdeti_reminders"

    private const val REMINDER_OFFSET = 30 * 60 * 1000L

    fun synchronize(
        context: Context,
        events: List<ScheduleEvent>
    ) {

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val storedEvents = prefs.all.keys
            .filter { it.startsWith("event_") }
            .associateWith {
                prefs.getLong(it, -1L)
            }

        val currentIds = events.map {
            "event_${it.itemType}_${it.itemId}"
        }.toSet()

        /*
         * Удаляем reminders объектов,
         * которых больше нет на сервере.
         */
        for ((key, _) in storedEvents) {

            if (key !in currentIds) {

                val eventKey = key.removePrefix("event_")

                val separatorIndex = eventKey.indexOf('_')

                if (separatorIndex > 0) {

                    val itemType = eventKey.substring(
                        0,
                        separatorIndex
                    )

                    val itemId = eventKey.substring(
                        separatorIndex + 1
                    )

                    cancel(
                        context,
                        itemType,
                        itemId
                    )
                }

                prefs.edit()
                    .remove(key)
                    .apply()
            }
        }

        /*
         * Создаём или обновляем reminders.
         */
        for (event in events) {

            val reminderTime =
                event.startTime - REMINDER_OFFSET

            /*
             * Если напоминание уже прошло —
             * ничего не создаём.
             */
            if (reminderTime <= System.currentTimeMillis()) {
                continue
            }

            val key =
                "event_${event.itemType}_${event.itemId}"

            val oldReminderTime =
                prefs.getLong(key, -1L)

            /*
             * Время не изменилось —
             * уже существующий reminder оставляем.
             */
            if (oldReminderTime == reminderTime) {
                continue
            }

            /*
             * Время изменилось.
             *
             * Значит занятие/событие было перенесено.
             */
            if (oldReminderTime != -1L) {

                cancel(
                    context,
                    event.itemType,
                    event.itemId
                )
            }

            schedule(
                context,
                event,
                reminderTime
            )

            prefs.edit()
                .putLong(key, reminderTime)
                .apply()
        }

        Log.d(
            TAG,
            "Синхронизация reminders завершена. Событий: ${events.size}"
        )
    }

    private fun schedule(
        context: Context,
        event: ScheduleEvent,
        reminderTime: Long
    ) {

        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val requestCode = createRequestCode(
            event.itemType,
            event.itemId
        )

        val intent = Intent(
            context,
            ReminderReceiver::class.java
        ).apply {

            putExtra("item_id", event.itemId)
            putExtra("item_type", event.itemType)

            putExtra("title", event.title)
            putExtra("student_name", event.studentName)

            putExtra("start_time", event.startTime)

            putExtra("lesson_kind", event.lessonKind)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        /*
         * Нам не нужна точность до секунды.
         *
         * setAndAllowWhileIdle позволяет alarm
         * сработать даже при Doze.
         */
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminderTime,
            pendingIntent
        )

        Log.d(
            TAG,
            "Reminder установлен: ${event.title} -> $reminderTime"
        )
    }

    fun cancel(
        context: Context,
        itemType: String,
        itemId: String
    ) {

        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(
            context,
            ReminderReceiver::class.java
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            createRequestCode(itemType, itemId),
            intent,
            PendingIntent.FLAG_NO_CREATE or
                    PendingIntent.FLAG_IMMUTABLE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }

        Log.d(
            TAG,
            "Reminder отменён: $itemType/$itemId"
        )
    }

    private fun createRequestCode(
        itemType: String,
        itemId: String
    ): Int {

        return "$itemType:$itemId".hashCode()
            .and(0x7fffffff)
    }
}