package com.example.registarappointmentsystem.data.fake

import com.example.registarappointmentsystem.data.model.StudentProfile
import com.example.registarappointmentsystem.data.repository.ProfileRepository
import kotlinx.coroutines.delay
// ITO YUNG MGA FAKE INFORMATION NATIN, READY TO SWAP FOR API CALLS

class FakeProfileRepository : ProfileRepository {
    override suspend fun getProfileForUser(userId: Int): StudentProfile {
        // Simulate a small network delay
        delay(300)
        return StudentProfile(
            firstName = "Juan",
            middleName = "Dela",
            lastName = "Cruz",
            extensionName = "Jr.",
            birthday = "January 10, 2000",
            gender = "Male",
            address = "Arellano St, Dagupan City, Pangasinan",
            studentNumber = "01-2334-234423",
            contactNumber = "09123456789"
        )
    }
}

