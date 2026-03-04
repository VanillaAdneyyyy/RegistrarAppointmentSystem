package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.model.DocumentType

interface AppointmentRepository {
    suspend fun getAppointmentsForUser(userId: Int): List<Appointment>
    suspend fun getAllAppointments(): List<Appointment>
    suspend fun createAppointment(appointment: Appointment): Result<Appointment>
    suspend fun cancelAppointment(appointmentId: Int): Result<Boolean>
    suspend fun updateAppointment(appointmentId: Int, fields: Map<String, String>): Result<Boolean>
    suspend fun requestCredentials(username: String, credentialType: String, reason: String, contactNumber: String, userId: Int = -1, documentTypeIds: List<Int> = emptyList(), studentIdNumber: String? = null): Result<Unit>
    suspend fun getDocumentTypes(): List<DocumentType>
}
