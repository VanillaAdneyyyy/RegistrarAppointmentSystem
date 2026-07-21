package com.example.registarappointmentsystem.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.registarappointmentsystem.R

class NotificationHelper(private val context: Context) {
    
    companion object {
        private const val APPOINTMENT_CHANNEL_ID = "digireg_appointments"
        private const val PAYMENT_CHANNEL_ID = "digireg_payment"
        private const val SESSION_CHANNEL_ID = "digireg_session"
        private const val APPOINTMENT_BASE_ID = 1000
        private const val SESSION_NOTIF_ID = 2
        private const val PAYMENT_BASE_ID = 3000
    }

    private fun eventNotifId(base: Int, key: String): Int {
        // Stable IDs dedupe repeat alerts for the same logical event.
        val hash = kotlin.math.abs(key.hashCode() % 9000)
        return base + hash
    }

    init {
        setupNotificationChannels()
    }

    private fun setupNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appointmentChannel = NotificationChannel(
                APPOINTMENT_CHANNEL_ID,
                "Appointment Status Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications about your document appointment status changes"
            }

            val paymentChannel = NotificationChannel(
                PAYMENT_CHANNEL_ID,
                "Payment Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications about payment requirements and status"
            }

            val sessionChannel = NotificationChannel(
                SESSION_CHANNEL_ID,
                "Session Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts about session expiration"
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(appointmentChannel)
            manager.createNotificationChannel(paymentChannel)
            manager.createNotificationChannel(sessionChannel)
        }
    }

    /**
     * Send notification for appointment approval
     */
    fun notifyApproved(docType: String, readyDate: String, isPaymentPending: Boolean = false) {
        try {
            val (title, contentText, bigText) = if (isPaymentPending) {
                Triple(
                    "✅ Request Approved (Payment Pending)",
                    "Your $docType request has been approved.",
                    "Your document request for $docType has been approved!\n\n⚠️ However, payment of the required amount is still pending.\n\nOnce your payment is verified, you will be able to schedule your pickup."
                )
            } else {
                Triple(
                    "✅ Request Approved",
                    "Your $docType request has been approved.",
                    "Your document request for $docType has been approved!\n\nAvailable from: $readyDate\n\nYou can now schedule your pickup."
                )
            }
            
            val notification = NotificationCompat.Builder(context, APPOINTMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val notifId = eventNotifId(APPOINTMENT_BASE_ID, "approved_$docType")
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    /**
     * Send notification for payment requirement
     */
    fun notifyPaymentRequired(docType: String, amount: String) {
        try {
            val notification = NotificationCompat.Builder(context, PAYMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("💳 Payment Required")
                .setContentText("₱$amount is required for your $docType")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Payment of ₱$amount is required for your $docType before your documents can be processed.\n\nPlease submit your GCash or bank transfer reference."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val notifId = eventNotifId(PAYMENT_BASE_ID, "required_$docType")
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    /**
     * Send notification for payment verification
     */
    fun notifyPaymentVerified(docType: String) {
        try {
            val notification = NotificationCompat.Builder(context, PAYMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("✓ Payment Verified")
                .setContentText("Your payment for $docType has been verified.")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Your payment for $docType has been verified by the registrar!\n\nYou can now proceed to schedule your pickup."))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val notifId = eventNotifId(PAYMENT_BASE_ID, "verified_$docType")
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    /**
     * Send notification when registrar waives the fee and credentials become free.
     */
    fun notifyPaymentWaived(docType: String) {
        try {
            val notification = NotificationCompat.Builder(context, PAYMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("🎉 Credentials Now Free")
                .setContentText("Your $docType request is now free. You can book date & time.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Good news! The registrar marked your $docType request as FREE.\n\nYou no longer need to pay. You can now schedule your booking date and time."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val notifId = eventNotifId(PAYMENT_BASE_ID, "waived_$docType")
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    /**
     * Send notification for ready for pickup
     */
    fun notifyReadyForPickup(docType: String) {
        try {
            val notification = NotificationCompat.Builder(context, APPOINTMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("📋 Ready for Pickup")
                .setContentText("Your $docType is ready for pickup!")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Your document for $docType is now ready for pickup at the Registrar's Office.\n\nPlease schedule your pickup time."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val notifId = eventNotifId(APPOINTMENT_BASE_ID, "ready_$docType")
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    /**
     * Send notification for rejection
     */
    fun notifyRejected(docType: String, reason: String) {
        try {
            val notification = NotificationCompat.Builder(context, APPOINTMENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("✕ Request Rejected")
                .setContentText("Your $docType request has been rejected.")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Your document request for $docType has been rejected.\n\nReason: $reason"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val notifId = eventNotifId(APPOINTMENT_BASE_ID, "rejected_$docType")
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }

    /**
     * Send notification for session expiration
     */
    fun notifySessionExpired() {
        try {
            val notification = NotificationCompat.Builder(context, SESSION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏱ Session Expired")
                .setContentText("Your session has expired due to inactivity. Please log in again.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(SESSION_NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        }
    }
}
