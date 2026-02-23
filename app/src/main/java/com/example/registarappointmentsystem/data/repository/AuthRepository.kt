package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.User
import com.example.registarappointmentsystem.data.remote.response.RequestPinResponse
import com.example.registarappointmentsystem.data.remote.response.ResetPasswordResponse
import com.example.registarappointmentsystem.data.remote.response.VerifyPinResponse
import retrofit2.Response

/**
 * Interface for authentication and user data operations.
 */
interface AuthRepository {

    /**
     * Logs in a user with the given credentials.
     * @param username The user's username or email.
     * @param password The user's password.
     * @return A Result containing the User on success or an Exception on failure.
     */
    suspend fun login(username: String, password: String): Result<User>

    /**
     * Fetches a user profile based on their email address.
     * @param email The email to search for.
     * @return A Result containing the User on success or an Exception on failure.
     */
    suspend fun getUserByEmail(email: String): Result<User>

    /**
     * Registers a new user in the system.
     * Logic for this should match your AuthRepositoryImpl implementation.
     */
    suspend fun register(
        firstName: String,
        middleName: String?,
        lastName: String,
        extensionName: String?,
        email: String,
        password: String,
        role: com.example.registarappointmentsystem.data.model.Role,
        idNumber: String?,
        employeeNumber: String?
    ): Result<Pair<Boolean, String>>

    /**
     * Requests a password reset PIN to be sent to user's email.
     * @param identifier The user's identifier (email or student ID).
     * @param idNumber Optional student ID number.
     * @param role The user's role (student, guest, etc.).
     * @param email The user's email address.
     * @return A Result containing the RequestPinResponse on success.
     */
    suspend fun requestPasswordResetPin(
        identifier: String,
        idNumber: String?,
        role: String,
        email: String
    ): Result<RequestPinResponse>

    /**
     * Verifies the PIN entered by the user.
     * @param requestId The password reset request ID.
     * @param pin The 6-digit PIN entered by the user.
     * @return A Result containing the VerifyPinResponse on success.
     */
    suspend fun verifyPin(requestId: Int, pin: String): Result<VerifyPinResponse>

    /**
     * Resets the password after PIN verification.
     * @param requestId The password reset request ID.
     * @param newPassword The new password to set.
     * @return A Result containing the ResetPasswordResponse on success.
     */
    suspend fun resetPassword(requestId: Int, newPassword: String): Result<ResetPasswordResponse>

    /**
     * Requests an email verification PIN to be sent to user's email.
     * @param request Map containing email and first_name.
     * @return Response containing success status and message.
     */
    suspend fun requestEmailVerificationPin(request: Map<String, String>): Response<Map<String, Any>>

    /**
     * Verifies the email PIN entered by the user.
     * @param request Map containing email and pin.
     * @return Response containing success status and verified flag.
     */
    suspend fun verifyEmailPin(request: Map<String, String>): Response<Map<String, Any>>
}
