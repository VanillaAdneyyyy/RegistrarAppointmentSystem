package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.remote.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppointmentRepositoryImpl(private val apiService: ApiService) : AppointmentRepository {

    override suspend fun getAppointmentsForUser(userId: Int): List<Appointment> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getAppointments()
                if (response.isSuccessful) {
                    response.body() ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    override suspend fun getAllAppointments(): List<Appointment> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getAppointments()
                if (response.isSuccessful) {
                    response.body() ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    override suspend fun createAppointment(appointment: Appointment): Result<Appointment> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.createAppointment(appointment)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null && body["success"] == true) {
                        Result.success(appointment.copy(id = body["id"] as? Int))
                    } else {
                        Result.failure(Exception(body?.get("message") as? String ?: "Failed to create appointment"))
                    }
                } else {
                    Result.failure(Exception("Failed to create appointment: ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun cancelAppointment(appointmentId: Int): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.cancelAppointment(appointmentId)
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Failed to cancel appointment: ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun requestCredentials(
        credentialType: String,
        reason: String
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Create appointment for credential request
                val appointment = Appointment(
                    username = null, // Will be set by backend or passed from caller
                    purpose = "$credentialType: $reason",
                    status = "pending"
                )

                val response = apiService.createAppointment(appointment)

                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to request credentials: ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
