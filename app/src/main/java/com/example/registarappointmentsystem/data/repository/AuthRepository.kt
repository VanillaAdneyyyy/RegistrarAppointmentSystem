package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.User

/**
 * Backend-ready contract for authentication operations.
 * - Now: implemented by FakeAuthRepository
 * - Later: implement with real PHP REST API calls.
 */
interface AuthRepository {
    suspend fun login(username: String, password: String): Result<User>
    // suspend fun register(data: RegisterRequest): Result<User> // to be added with register flow
}

