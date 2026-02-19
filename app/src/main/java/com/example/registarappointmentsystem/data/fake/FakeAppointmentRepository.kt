package com.example.registarappointmentsystem.data.fake

import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.model.AppointmentStatus
import com.example.registarappointmentsystem.data.repository.AppointmentRepository
import kotlinx.coroutines.delay
// ITO YUNG MGA FAKE INFORMATION NATIN, READY TO SWAP FOR API CALLS
class FakeAppointmentRepository : AppointmentRepository {

    private val fakeAppointments = listOf(
        Appointment(
            id = 1,
            referenceId = 30,
            registrarNote = "Registrar's Note\nPlease bring your necessary requirements...",
            availableDate = "January 30, 2026",
            status = AppointmentStatus.APPROVED
        ),
        Appointment(
            id = 2,
            referenceId = 29,
            registrarNote = "The School Registrar is currently reviewing your request. Try to check it later.",
            availableDate = null,
            status = AppointmentStatus.PENDING
        )
    )

    override suspend fun getAppointmentsForUser(userId: Int): List<Appointment> {
        // Simulate network delay
        delay(400)
        return fakeAppointments
    }

    override suspend fun requestCredentials(
        credentialType: String,
        reason: String
    ): Result<Unit> {
        // Simulate network delay and always succeed for now.
        delay(400)
        return Result.success(Unit)
    }
}

