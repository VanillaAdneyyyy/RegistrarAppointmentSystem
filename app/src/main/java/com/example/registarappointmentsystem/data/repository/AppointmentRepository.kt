package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.Appointment

interface AppointmentRepository {
    suspend fun getAppointmentsForUser(userId: Int): List<Appointment>
    suspend fun requestCredentials(credentialType: String, reason: String): Result<Unit>
}

