package com.itdeti.assistant

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ScheduleSyncWorkerStarter {

    private const val PERIODIC_WORK_NAME =
        "itdeti_schedule_sync"

    private const val IMMEDIATE_WORK_NAME =
        "itdeti_schedule_sync_now"

    fun startNow(context: Context) {

        val request =
            OneTimeWorkRequestBuilder<ScheduleSyncWorker>()
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }

    fun schedulePeriodic(context: Context) {

        /*
         * Android сам определяет точное время запуска
         * в рамках ограничений WorkManager.
         *
         * 6 часов достаточно как резервный механизм.
         *
         * Основная синхронизация происходит при запуске
         * приложения и возвращении из background.
         */
        val request =
            PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                6,
                TimeUnit.HOURS
            )
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }
}