package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AuthRepositoryImpl
import kotlinx.coroutines.launch

class ForgotPasswordViewModel(
    private val authRepository: AuthRepositoryImpl
) : ViewModel() {

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _resetSent = MutableLiveData(false)
    val resetSent: LiveData<Boolean> = _resetSent

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    /**
     * Request password reset by email only
     * Simplified flow - just send email, backend handles the reset link
     */
    fun requestPasswordReset(email: String) {
        _isLoading.value = true
        _errorMessage.value = null
        
        viewModelScope.launch {
            // Use the existing API but with email only
            // The backend will send a reset link to the email
            val result = authRepository.requestPasswordResetPin(
                identifier = email,
                idNumber = null,
                role = "guest", // Default role, backend will determine from email
                email = email
            )
            
            _isLoading.value = false
            
            result.onSuccess { response ->
                if (response.success) {
                    _resetSent.value = true
                } else {
                    _errorMessage.value = response.message ?: "Failed to send reset link"
                }
            }.onFailure { exception ->
                _errorMessage.value = exception.message ?: "Failed to send reset link. Please try again."
            }
        }
    }
}

class ForgotPasswordViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ForgotPasswordViewModel::class.java)) {
            val repo = AuthRepositoryImpl(RetrofitClient.apiService)
            return ForgotPasswordViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
