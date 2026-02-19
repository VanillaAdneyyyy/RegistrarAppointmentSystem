package com.example.registarappointmentsystem.data.model

enum class AppointmentStatus {
    APPROVED,
    PENDING,
    REJECTED,
    COMPLETED
}

data class Appointment(
    val id: Int,
    val referenceId: Int,
    val registrarNote: String?,
    val availableDate: String?,
    val status: AppointmentStatus
)

