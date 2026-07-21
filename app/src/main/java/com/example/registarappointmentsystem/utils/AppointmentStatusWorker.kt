package com.example.registarappointmentsystem.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl

class AppointmentStatusWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private fun isSessionStillActive(expectedUserId: Int): Boolean {
        val authPrefs = applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
        return authPrefs.getInt("user_id", -1) == expectedUserId
    }

    override suspend fun doWork(): Result {
        val authPrefs = applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val userId = authPrefs.getInt("user_id", -1)
        if (userId <= 0) return Result.success()

        val statusUpdatesEnabled = authPrefs.getBoolean("pref_status_updates", true)
        val readyPickupEnabled = authPrefs.getBoolean("pref_ready_pickup", true)

        val repository = AppointmentRepositoryImpl(RetrofitClient.apiService)
        val notificationHelper = NotificationHelper(applicationContext)
        val cache = applicationContext.getSharedPreferences("appointment_status_cache", Context.MODE_PRIVATE)
        val editor = cache.edit()

        return try {
            val appointments = repository.getAppointmentsForUser(userId)

            appointments.forEach { appt ->
                if (!isSessionStillActive(userId)) return Result.success()

                val apptId = appt.id ?: return@forEach
                val cacheKeyStatus = "status_$apptId"
                val cacheKeyPaymentStatus = "payment_status_$apptId"
                val cacheKeyPaymentAmount = "payment_amount_$apptId"

                val prevStatus = cache.getString(cacheKeyStatus, null)
                val prevPaymentStatus = cache.getString(cacheKeyPaymentStatus, null)
                val prevPaymentAmount = cache.getString(cacheKeyPaymentAmount, null)

                val currentStatus = appt.status
                val currentPaymentStatus = appt.paymentStatus
                val prevPayAmt = prevPaymentAmount?.toDoubleOrNull() ?: 0.0
                val currPayAmt = appt.paymentAmount?.toDoubleOrNull() ?: 0.0

                if (prevStatus == null) {
                    editor.putString(cacheKeyStatus, currentStatus)
                    editor.putString(cacheKeyPaymentStatus, currentPaymentStatus)
                    editor.putString(cacheKeyPaymentAmount, appt.paymentAmount)
                    return@forEach
                }

                if (statusUpdatesEnabled && prevPayAmt > 0.0 && currPayAmt <= 0.0) {
                    notificationHelper.notifyPaymentWaived(appt.purpose ?: "Document")
                }

                if (statusUpdatesEnabled && prevPaymentStatus != currentPaymentStatus) {
                    if (currentPaymentStatus == "pending" && currPayAmt > 0.0) {
                        notificationHelper.notifyPaymentRequired(
                            appt.purpose ?: "Document",
                            String.format("%.2f", currPayAmt)
                        )
                    }
                    if (currentPaymentStatus == "verified") {
                        notificationHelper.notifyPaymentVerified(appt.purpose ?: "Document")
                    }
                }

                if (prevStatus != currentStatus) {
                    when (currentStatus?.lowercase()) {
                        "approved" -> {
                            if (statusUpdatesEnabled) {
                                val isPaymentPending = currentPaymentStatus?.lowercase() == "pending"
                                notificationHelper.notifyApproved(
                                    appt.purpose ?: "Document",
                                    appt.ready_date ?: "",
                                    isPaymentPending
                                )
                            }
                        }
                        "ready", "for_claiming" -> {
                            if (statusUpdatesEnabled && readyPickupEnabled) {
                                notificationHelper.notifyReadyForPickup(appt.purpose ?: "Document")
                            }
                        }
                        "rejected" -> {
                            if (statusUpdatesEnabled) {
                                notificationHelper.notifyRejected(
                                    appt.purpose ?: "Document",
                                    appt.admin_comment ?: "Please contact the registrar for details."
                                )
                            }
                        }
                    }
                }

                editor.putString(cacheKeyStatus, currentStatus)
                editor.putString(cacheKeyPaymentStatus, currentPaymentStatus)
                editor.putString(cacheKeyPaymentAmount, appt.paymentAmount)
            }

            editor.apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
