package com.example.registarappointmentsystem.data.remote.response

data class VerifyPinResponse(
    val success: Boolean,
    val message: String,
    val verified: Boolean? = null,
    val userId: Int? = null
)
