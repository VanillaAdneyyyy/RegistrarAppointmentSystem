package com.example.registarappointmentsystem.data.model

enum class AppointmentStatus {
    PENDING,
    APPROVED,
    READY,
    REJECTED,
    COMPLETED,
    INCOMPLETE
}

data class Appointment(
    val id: Int? = null,
    val user_id: Int? = null,
    val username: String? = null,
    val date: String? = null,
    val time: String? = null,
    val purpose: String? = null,
    val contact_number: String? = null,
    val status: String? = "pending",
    val admin_comment: String? = null,
    val ready_date: String? = null,
    val created_at: String? = null,
    val reason: String? = null,
    val pickup_time: String? = null,
    val student_pickup_date: String? = null,
    val firebase_id: String? = null,
    val updated_at: String? = null,
    @com.google.gson.annotations.SerializedName("payment_amount")
    val paymentAmount: String? = null,
    @com.google.gson.annotations.SerializedName("payment_reference")
    val paymentReference: String? = null,
    @com.google.gson.annotations.SerializedName("payment_status")
    val paymentStatus: String? = null,
    @com.google.gson.annotations.SerializedName("payment_deadline")
    val paymentDeadline: String? = null,
    @com.google.gson.annotations.SerializedName("claiming_deadline")
    val claimingDeadline: String? = null,
    @com.google.gson.annotations.SerializedName("document_items")
    val documentItems: List<DocumentItem>? = null,
    @com.google.gson.annotations.SerializedName("document_type_ids")
    val documentTypeIds: List<Int>? = null,
    @com.google.gson.annotations.SerializedName("student_id_number")
    val studentIdNumber: String? = null,
    val reference_number: String? = null
) {
    // Helper to get status as enum
    fun getStatusEnum(): AppointmentStatus {
        return when (status?.lowercase()) {
            "pending" -> AppointmentStatus.PENDING
            "approved" -> AppointmentStatus.APPROVED
            "ready" -> AppointmentStatus.READY
            "rejected" -> AppointmentStatus.REJECTED
            "completed" -> AppointmentStatus.COMPLETED
            "incomplete" -> AppointmentStatus.INCOMPLETE
            else -> AppointmentStatus.PENDING
        }
    }
    
    // Helper to check if cancellable
    fun isCancellable(): Boolean {
        return status == "pending" || status == "ready" || status.isNullOrEmpty()
    }

    /**
     * Returns a human-readable countdown to the payment deadline, e.g. "⏰ Auto-cancel in 23h 45m".
     * Returns null if no deadline is set, or an "expired" string if the deadline has already passed.
     */
    /**
     * Returns a human-readable countdown for the 7-day walk-in claiming window after no-show.
     * e.g. "You have 5 days left to walk in and claim your documents."
     */
    fun getClaimingDeadlineRemainingText(): String? {
        val raw = claimingDeadline ?: return null
        val deadlineMs = try {
            val formats = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            var ms: Long? = null
            for (fmt in formats) {
                ms = try {
                    val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    sdf.parse(raw)?.time
                } catch (_: Exception) { null }
                if (ms != null) break
            }
            ms ?: return null
        } catch (_: Exception) { return null }

        val diffMs = deadlineMs - System.currentTimeMillis()
        val dateFmt = java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault())
        dateFmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val formattedDate = dateFmt.format(java.util.Date(deadlineMs))
        return if (diffMs <= 0) {
            "⚠️ Walk-in window has expired — request will be cancelled shortly"
        } else {
            val days = diffMs / (1000L * 60 * 60 * 24)
            val hours = (diffMs % (1000L * 60 * 60 * 24)) / (1000L * 60 * 60)
            if (days > 0) "⏰ Walk-in window: $days day${if (days != 1L) "s" else ""} remaining (by $formattedDate) — claim before it expires!"
            else "⏰ Walk-in window: ${hours}h remaining (by $formattedDate) — go claim your documents now!"
        }
    }

    fun getDeadlineRemainingText(): String? {
        val raw = paymentDeadline ?: return null
        val deadlineMs = try {
            // Try with milliseconds first, then without
            val formats = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            var ms: Long? = null
            for (fmt in formats) {
                ms = try {
                    val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    sdf.parse(raw)?.time
                } catch (_: Exception) { null }
                if (ms != null) break
            }
            ms ?: return null
        } catch (_: Exception) { return null }

        val diffMs = deadlineMs - System.currentTimeMillis()
        return if (diffMs <= 0) {
            "⏰ Deadline has passed — awaiting cancellation"
        } else {
            val hours = diffMs / (1000L * 60 * 60)
            val mins  = (diffMs % (1000L * 60 * 60)) / (1000L * 60)
            "⏰ Auto-cancel in ${hours}h ${mins}m — submit proof before it expires!"
        }
    }
}
