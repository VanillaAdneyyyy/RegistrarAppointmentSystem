package com.example.registarappointmentsystem.data.remote.request

data class RequestPinRequest(
    val identifier: String,
    val idNumber: String? = null,
    val role: String,
    val email: String
)
