package com.example.registarappointmentsystem.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackgroundSyncScheduler {
    private const val PERIODIC_WORK_NAME = "digireg_appointment_status_periodic"
    private const val IMMEDIATE_WORK_NAME = "digireg_appointment_status_once"

    fun start(context: Context) {
        val authPrefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val userId = authPrefs.getInt("user_id", -1)
        if (userId <= 0) {
            stop(context)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<AppointmentStatusWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val immediateWork = OneTimeWorkRequestBuilder<AppointmentStatusWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                immediateWork
            )
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }
}
