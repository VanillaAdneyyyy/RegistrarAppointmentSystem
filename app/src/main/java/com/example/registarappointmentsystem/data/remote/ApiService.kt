package com.example.registarappointmentsystem.data.remote

import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.model.DocumentType
import com.example.registarappointmentsystem.data.model.TimeSlotsResponse
import com.example.registarappointmentsystem.data.model.User
import com.example.registarappointmentsystem.data.remote.request.LoginRequest
import com.example.registarappointmentsystem.data.remote.request.RegisterRequest
import com.example.registarappointmentsystem.data.remote.request.RequestPinRequest
import com.example.registarappointmentsystem.data.remote.request.ResetPasswordRequest
import com.example.registarappointmentsystem.data.remote.request.VerifyPinRequest
import com.example.registarappointmentsystem.data.remote.response.LoginResponse
import com.example.registarappointmentsystem.data.remote.response.RegisterResponse
import com.example.registarappointmentsystem.data.remote.response.RequestPinResponse
import com.example.registarappointmentsystem.data.remote.response.ResetPasswordResponse
import com.example.registarappointmentsystem.data.remote.response.VerifyPinResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query


interface ApiService {

    @POST("api/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>

    @POST("api/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<RegisterResponse>


    @GET("api/auth/users")
    suspend fun getUserByEmail(
        @Query("email") email: String
    ): Response<List<User>>


    // Password Reset Endpoints
    @POST("api/auth/request-reset-pin")
    suspend fun requestPasswordResetPin(
        @Body request: RequestPinRequest
    ): Response<RequestPinResponse>


    @POST("api/auth/verify-pin")
    suspend fun verifyPin(
        @Body request: VerifyPinRequest
    ): Response<VerifyPinResponse>

    @POST("api/auth/reset-password")
    suspend fun resetPassword(
        @Body request: ResetPasswordRequest
    ): Response<ResetPasswordResponse>

    @GET("api/appointments/document-types")
    suspend fun getDocumentTypes(): Response<List<DocumentType>>

    @GET("api/appointments")
    suspend fun getAppointments(): Response<List<Appointment>>

    @GET("api/appointments/user/{userId}")
    suspend fun getAppointmentsByUser(@Path("userId") userId: Int): Response<List<Appointment>>


    @POST("api/appointments")
    suspend fun createAppointment(@Body appointment: Appointment): Response<Map<String, Any>>

    @PUT("api/appointments/{id}/cancel")
    suspend fun cancelAppointment(@Path("id") id: Int): Response<Unit>

    @PUT("api/appointments/{id}")
    suspend fun updateAppointment(
        @Path("id") id: Int,
        @Body body: Map<String, String>
    ): Response<Map<String, Any>>

    // Email Verification Endpoints
    @POST("api/auth/request-email-verification-pin")
    suspend fun requestEmailVerificationPin(
        @Body request: Map<String, String>
    ): Response<Map<String, Any>>

    @POST("api/auth/verify-email-pin")
    suspend fun verifyEmailPin(
        @Body request: Map<String, String>
    ): Response<Map<String, Any>>

    /** Returns all slots for a date with their availability status */
    @GET("api/appointments/time-slots/{date}")
    suspend fun getTimeSlots(@Path("date") date: String): Response<TimeSlotsResponse>

    /** Marks a specific slot as booked in the time_slots table */
    @POST("api/appointments/time-slots/book")
    suspend fun bookTimeSlot(@Body body: Map<String, String>): Response<Map<String, Any>>

    @Multipart
    @POST("api/appointments/{id}/upload-proof")
    suspend fun uploadPaymentProof(
        @Path("id") id: Int,
        @Part screenshot: MultipartBody.Part
    ): Response<Map<String, Any>>
}
