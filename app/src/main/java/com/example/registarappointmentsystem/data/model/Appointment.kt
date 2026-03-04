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
    @com.google.gson.annotations.SerializedName("document_items")
    val documentItems: List<DocumentItem>? = null,
    @com.google.gson.annotations.SerializedName("document_type_ids")
    val documentTypeIds: List<Int>? = null,
    @com.google.gson.annotations.SerializedName("student_id_number")
    val studentIdNumber: String? = null
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
}
