package com.example.registarappointmentsystem.data.remote.response

import com.example.registarappointmentsystem.data.model.User

data class LoginResponse(
    val success: Boolean,
    val message: String? = null,
    val user: User? = null
)
