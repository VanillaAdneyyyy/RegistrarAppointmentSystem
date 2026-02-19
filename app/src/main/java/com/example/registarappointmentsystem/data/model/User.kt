package com.example.registarappointmentsystem.data.model

enum class Role {
    STUDENT,
    ADMIN,
    REGISTRAR,
    GUEST
}

data class User(
    val id: Int,
    val name: String,
    val email: String,
    val role: Role,
    val token: String? = null
)

