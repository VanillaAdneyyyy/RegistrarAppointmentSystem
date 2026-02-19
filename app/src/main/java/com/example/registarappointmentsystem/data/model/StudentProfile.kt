package com.example.registarappointmentsystem.data.model

data class StudentProfile(
    val firstName: String,
    val middleName: String?,
    val lastName: String,
    val extensionName: String?,
    val birthday: String,
    val gender: String,
    val address: String,
    val studentNumber: String,
    val contactNumber: String,
    val maskedPassword: String = "********"
)

