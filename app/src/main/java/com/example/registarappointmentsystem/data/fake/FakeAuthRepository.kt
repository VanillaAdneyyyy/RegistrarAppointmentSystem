package com.example.registarappointmentsystem.data.fake

import com.example.registarappointmentsystem.data.model.Role
import com.example.registarappointmentsystem.data.model.User
import com.example.registarappointmentsystem.data.repository.AuthRepository
import kotlinx.coroutines.delay

// ITO YUNG MGA FAKE INFORMATION NATIN, READY TO SWAP FOR API CALLS

class FakeAuthRepository : AuthRepository {

    private val users = mutableListOf(
        User(
            id = 1,
            name = "Student User",
            email = "student@example.com",
            role = Role.STUDENT,
            token = "fake-token-student"
        ),
        User(
            id = 2,
            name = "Admin User",
            email = "admin@example.com",
            role = Role.ADMIN,
            token = "fake-token-admin"
        )
    )

    override suspend fun login(username: String, password: String): Result<User> {
        // Simulate network delay
        delay(500)

        // DEMO/MOCK MODE:
        // Allow ANY non-empty username + password to log in
        // as a "Student User" so you can always reach the dashboard.
        if (username.isNotBlank() && password.isNotBlank()) {
            val demoUser = User(
                id = 1,
                name = "Student User",
                email = username,
                role = Role.STUDENT,
                token = "fake-token-student"
            )
            return Result.success(demoUser)
        }

        return Result.failure(
            IllegalArgumentException("Invalid credentials. Please enter both email and password.")
        )
    }
}

