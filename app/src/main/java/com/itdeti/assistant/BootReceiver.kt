package com.itdeti.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        when (intent.action) {

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {

                /*
                 * Восстанавливаем NotificationService.
                 */
                try {

                    val serviceIntent =
                        Intent(
                            context,
                            NotificationService::class.java
                        )

                    context.startForegroundService(
                        serviceIntent
                    )

                } catch (_: Exception) {
                    // Android может ограничить запуск
                    // foreground service после boot.
                }

                /*
                 * Сразу ставим задачу синхронизации.
                 */
                ScheduleSyncWorkerStarter.startNow(context)

                /*
                 * И регистрируем периодическую синхронизацию.
                 */
                ScheduleSyncWorkerStarter.schedulePeriodic(
                    context
                )
            }
        }
    }
}