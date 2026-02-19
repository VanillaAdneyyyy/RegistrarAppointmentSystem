package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.registarappointmentsystem.core.UiResult
import com.example.registarappointmentsystem.data.fake.FakeProfileRepository
import com.example.registarappointmentsystem.data.model.StudentProfile
import com.example.registarappointmentsystem.data.repository.ProfileRepository
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val _profileState = MutableLiveData(UiResult<StudentProfile>(isLoading = true))
    val profileState: LiveData<UiResult<StudentProfile>> = _profileState

    fun loadProfile(userId: Int) {
        _profileState.value = UiResult(isLoading = true)
        viewModelScope.launch {
            val profile = profileRepository.getProfileForUser(userId)
            _profileState.value = UiResult(data = profile)
        }
    }
}

class ProfileViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            val repo = FakeProfileRepository()
            return ProfileViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

