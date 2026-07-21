package com.example.registarappointmentsystem.data.remote.request

data class RequestGuestDeletionPinRequest(
    val userId: Int,
    val email: String
)

data class VerifyGuestDeletionPinRequest(
    val userId: Int,
    val email: String,
    val pin: String
)
