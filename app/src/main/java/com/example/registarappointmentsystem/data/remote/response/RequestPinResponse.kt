package com.example.registarappointmentsystem.data.remote.response

data class RequestPinResponse(
    val success: Boolean,
    val message: String,
    val requestId: Int? = null,
    val expiresAt: String? = null
)
