package com.example.registarappointmentsystem.data.remote.request

data class RegisterRequest(
    val first_name: String,
    val middle_name: String? = null,
    val last_name: String,
    val extension_name: String? = null,
    val email: String,
    val password: String,
    val role: String,
    val id_number: String? = null,
    val employee_number: String? = null,
    val is_admin_created: Boolean? = false
)
