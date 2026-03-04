package com.example.registarappointmentsystem.data.repository

import android.content.Context
import android.net.Uri
import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.model.DocumentType
import com.example.registarappointmentsystem.data.model.TimeSlot
import com.example.registarappointmentsystem.data.remote.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class AppointmentRepositoryImpl(private val apiService: ApiService) : AppointmentRepository {

    override suspend fun getAppointmentsForUser(userId: Int): List<Appointment> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getAppointmentsByUser(userId)
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

    override suspend fun updateAppointment(appointmentId: Int, fields: Map<String, String>): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.updateAppointment(appointmentId, fields)
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Failed to update appointment: ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Fetches time slot availability for a given ISO date (yyyy-MM-dd).
     * Falls back to an empty list (the caller falls back to local slots) if unreachable.
     */
    suspend fun getTimeSlots(date: String): List<TimeSlot> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getTimeSlots(date)
                if (response.isSuccessful) response.body()?.slots ?: emptyList()
                else emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    /** Books a slot in the time_slots table — also atomically sets status=ready on the appointment. */
    suspend fun bookTimeSlot(date: String, time: String, appointmentId: Int, username: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.bookTimeSlot(
                    mapOf(
                        "date"           to date,
                        "time"           to time,
                        "appointment_id" to appointmentId.toString(),
                        "username"       to username
                    )
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    val success = body?.get("success")
                    if (success == true || success?.toString() == "true") {
                        Result.success(Unit)
                    } else {
                        val msg = body?.get("message")?.toString()
                            ?: body?.get("error")?.toString()
                            ?: "Slot booking failed"
                        Result.failure(Exception(msg))
                    }
                } else {
                    Result.failure(Exception("Server error ${response.code()}: ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun requestCredentials(
        username: String,
        credentialType: String,
        reason: String,
        contactNumber: String,
        userId: Int,
        documentTypeIds: List<Int>,
        studentIdNumber: String?
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val appointment = Appointment(
                    user_id = if (userId > 0) userId else null,
                    username = username.ifBlank { null },
                    purpose = credentialType.ifBlank { null },
                    reason = reason,
                    contact_number = contactNumber.ifBlank { null },
                    status = "pending",
                    documentTypeIds = documentTypeIds.ifEmpty { null },
                    studentIdNumber = studentIdNumber?.ifBlank { null }
                )
                val response = apiService.createAppointment(appointment)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null && body["success"] == true) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception(body?.get("message") as? String ?: "Request failed"))
                    }
                } else {
                    val errorMsg = try {
                        val json = org.json.JSONObject(response.errorBody()?.string() ?: "{}")
                        json.optString("error").ifEmpty { "Failed to submit request: ${response.message()}" }
                    } catch (ex: Exception) {
                        "Failed to submit request: ${response.message()}"
                    }
                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun getDocumentTypes(): List<DocumentType> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getDocumentTypes()
                if (response.isSuccessful) response.body() ?: emptyList()
                else emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun uploadPaymentProof(appointmentId: Int, uri: Uri, context: Context): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open image"))
                val bytes = inputStream.readBytes()
                inputStream.close()
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("screenshot", "proof.jpg", requestBody)
                val response = apiService.uploadPaymentProof(appointmentId, part)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Upload failed: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun submitPayment(appointmentId: Int, reference: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val body = mapOf(
                    "status" to "approved",
                    "payment_reference" to reference,
                    "payment_status" to "submitted"
                )
                val response = apiService.updateAppointment(appointmentId, body)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Server error: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
