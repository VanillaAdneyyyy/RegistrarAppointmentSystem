package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.Appointment

interface AppointmentRepository {
    suspend fun getAppointmentsForUser(userId: Int): List<Appointment>
    suspend fun getAllAppointments(): List<Appointment>
    suspend fun createAppointment(appointment: Appointment): Result<Appointment>
    suspend fun cancelAppointment(appointmentId: Int): Result<Boolean>
    suspend fun requestCredentials(credentialType: String, reason: String): Result<Unit>
}
