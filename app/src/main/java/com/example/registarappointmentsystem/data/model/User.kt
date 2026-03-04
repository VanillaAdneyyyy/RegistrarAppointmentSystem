package com.example.registarappointmentsystem.data.model

enum class Role {
    STUDENT,
    ADMIN,
    REGISTRAR,
    GUEST
}

data class User(
    val id: Int,
    val first_name: String? = null,
    val middle_name: String? = null,
    val last_name: String? = null,
    val extension_name: String? = null,
    val name: String? = null, // Computed from first_name + last_name for backward compatibility
    val email: String,
    val personal_email: String? = null,
    val role: String, // Backend returns role as string: "student", "guest", "admin", etc.
    val token: String? = null,
    val status: String? = null,
    val is_active: Boolean? = null,
    val is_approved: Boolean? = null,
    val id_number: String? = null,
    val employee_number: String? = null,
    val student_number: String? = null,
    val contact_number: String? = null,
    val gender: String? = null,
    val birthday: String? = null,
    val address: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
) {
    // Helper to get full name
    fun getFullName(): String {
        return if (first_name != null && last_name != null) {
            "$first_name ${middle_name ?: ""} $last_name ${extension_name ?: ""}".trim()
        } else {
            name ?: email
        }
    }
    
    // Helper to get role as enum
    fun getRoleEnum(): Role {
        return when (role?.lowercase()) {
            "student" -> Role.STUDENT
            "admin" -> Role.ADMIN
            "registrar" -> Role.REGISTRAR
            "guest" -> Role.GUEST
            else -> Role.GUEST
        }
    }
}
