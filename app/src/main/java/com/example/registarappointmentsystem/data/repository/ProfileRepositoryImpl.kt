package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.StudentProfile
import com.example.registarappointmentsystem.data.remote.ApiService

class ProfileRepositoryImpl(
    private val apiService: ApiService
) : ProfileRepository {
    
    override suspend fun getProfileForUser(userId: Int): StudentProfile {
        // Since we don't have a direct profile endpoint, we'll need to get user by email
        // For now, return a placeholder - the actual implementation would need the user's email
        // This is a temporary implementation until the backend adds a /api/users/{id} endpoint
        
        // Note: The ProfileActivity currently uses AuthRepository.getUserByEmail() directly
        // This repository is kept for compatibility with ProfileViewModel
        throw NotImplementedError("Use AuthRepository.getUserByEmail() instead for now")
    }
    
    // Helper function to convert User to StudentProfile
    private fun mapUserToStudentProfile(user: com.example.registarappointmentsystem.data.model.User): StudentProfile {
        return StudentProfile(
            firstName = user.first_name ?: "",
            middleName = user.middle_name,
            lastName = user.last_name ?: "",
            extensionName = user.extension_name,
            birthday = user.birthday ?: "",
            gender = user.gender ?: "",
            address = user.address ?: "",
            studentNumber = user.student_number ?: "Not Student",
            contactNumber = user.contact_number ?: ""
        )
    }
}
