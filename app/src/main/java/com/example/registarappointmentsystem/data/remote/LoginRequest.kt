package com.example.registarappointmentsystem.data.remote.request

data class LoginRequest(
    val identifier: String,  // Can be email, id_number, or employee_number
    val password: String,
    val role: String  // "student", "employee", "guest", etc.
)
