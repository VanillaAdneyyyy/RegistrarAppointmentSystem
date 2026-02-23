package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.Role
import com.example.registarappointmentsystem.data.model.User
import com.example.registarappointmentsystem.data.remote.ApiService
import com.example.registarappointmentsystem.data.remote.request.LoginRequest
import com.example.registarappointmentsystem.data.remote.request.RegisterRequest
import com.example.registarappointmentsystem.data.remote.request.RequestPinRequest
import com.example.registarappointmentsystem.data.remote.request.ResetPasswordRequest
import com.example.registarappointmentsystem.data.remote.request.VerifyPinRequest
import com.example.registarappointmentsystem.data.remote.response.RequestPinResponse
import com.example.registarappointmentsystem.data.remote.response.ResetPasswordResponse
import com.example.registarappointmentsystem.data.remote.response.VerifyPinResponse
import retrofit2.Response

class AuthRepositoryImpl(private val apiService: ApiService) : AuthRepository {

    override suspend fun login(username: String, password: String): Result<User> {
        return try {
            val role: String = when {
                username.contains("@") && !username.contains("student") -> "guest"
                username.startsWith("STU", ignoreCase = true) -> "student"
                else -> "guest"
            }

            val request = LoginRequest(
                identifier = username,
                password = password,
                role = role
            )

            val response = apiService.login(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.user != null) {
                    Result.success(body.user)
                } else {
                    Result.failure(Exception(body?.message ?: "Login failed"))
                }
            } else {
                Result.failure(Exception("Login failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun register(
        firstName: String,
        middleName: String?,
        lastName: String,
        extensionName: String?,
        email: String,
        password: String,
        role: Role,
        idNumber: String?,
        employeeNumber: String?
    ): Result<Pair<Boolean, String>> {
        return try {
            val request = RegisterRequest(
                first_name = firstName,
                middle_name = middleName,
                last_name = lastName,
                extension_name = extensionName,
                email = email,
                password = password,
                role = role.name.lowercase(),
                id_number = idNumber,
                employee_number = employeeNumber,
                is_admin_created = false
            )

            val response = apiService.register(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    Result.success(Pair(true, body.message ?: "Registration successful"))
                } else {
                    Result.success(Pair(false, body?.message ?: "Registration failed"))
                }
            } else {
                Result.success(Pair(false, "Registration failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserByEmail(email: String): Result<User> {
        return try {
            val response = apiService.getUserByEmail(email)

            if (response.isSuccessful) {
                val users = response.body()
                if (!users.isNullOrEmpty()) {
                    Result.success(users[0])
                } else {
                    Result.failure(Exception("User not found"))
                }
            } else {
                Result.failure(Exception("Failed to get user: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isAllowedRole(role: Role): Boolean {
        return role == Role.STUDENT || role == Role.GUEST
    }

    // Forgot Password Methods
    override suspend fun requestPasswordResetPin(
        identifier: String,
        idNumber: String?,
        role: String,
        email: String
    ): Result<RequestPinResponse> {
        return try {
            val request = RequestPinRequest(
                identifier = identifier,
                idNumber = idNumber,
                role = role,
                email = email
            )
            
            val response = apiService.requestPasswordResetPin(request)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    Result.success(body)
                } else {
                    Result.failure(Exception(body?.message ?: "Failed to request PIN"))
                }
            } else {
                Result.failure(Exception("Failed to request PIN: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun verifyPin(requestId: Int, pin: String): Result<VerifyPinResponse> {
        return try {
            val request = VerifyPinRequest(
                requestId = requestId,
                pin = pin
            )
            
            val response = apiService.verifyPin(request)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    Result.success(body)
                } else {
                    Result.failure(Exception(body?.message ?: "Failed to verify PIN"))
                }
            } else {
                Result.failure(Exception("Failed to verify PIN: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetPassword(requestId: Int, newPassword: String): Result<ResetPasswordResponse> {
        return try {
            val request = ResetPasswordRequest(
                requestId = requestId,
                newPassword = newPassword
            )
            
            val response = apiService.resetPassword(request)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    Result.success(body)
                } else {
                    Result.failure(Exception(body?.message ?: "Failed to reset password"))
                }
            } else {
                Result.failure(Exception("Failed to reset password: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Email Verification Methods
    override suspend fun requestEmailVerificationPin(request: Map<String, String>): Response<Map<String, Any>> {
        return apiService.requestEmailVerificationPin(request)
    }

    override suspend fun verifyEmailPin(request: Map<String, String>): Response<Map<String, Any>> {
        return apiService.verifyEmailPin(request)
    }
}
