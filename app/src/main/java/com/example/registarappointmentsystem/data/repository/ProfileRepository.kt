package com.example.registarappointmentsystem.data.repository

import com.example.registarappointmentsystem.data.model.StudentProfile

interface ProfileRepository {
    suspend fun getProfileForUser(userId: Int): StudentProfile
}

