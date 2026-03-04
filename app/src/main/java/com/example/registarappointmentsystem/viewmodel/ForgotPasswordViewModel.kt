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

    // Step 1 result: PIN sent, expose requestId
    private val _resetSent = MutableLiveData(false)
    val resetSent: LiveData<Boolean> = _resetSent

    // Step 2 result: PIN verified
    private val _pinVerified = MutableLiveData(false)
    val pinVerified: LiveData<Boolean> = _pinVerified

    // Step 3 result: password reset completed
    private val _passwordReset = MutableLiveData(false)
    val passwordReset: LiveData<Boolean> = _passwordReset

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Internal state
    private var requestId: Int = -1

    /** Step 1 — request PIN sent to email */
    fun requestPasswordReset(email: String) {
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            val result = authRepository.requestPasswordResetPin(
                identifier = email,
                idNumber = null,
                role = "guest",
                email = email
            )

            _isLoading.value = false

            result.onSuccess { response ->
                if (response.success && response.requestId != null) {
                    requestId = response.requestId
                    _resetSent.value = true
                } else {
                    _errorMessage.value = response.message ?: "Failed to send PIN"
                }
            }.onFailure { exception ->
                _errorMessage.value = exception.message ?: "Failed to send PIN. Please try again."
            }
        }
    }

    /** Step 2 — verify the 6-digit PIN */
    fun verifyPin(pin: String) {
        if (requestId == -1) {
            _errorMessage.value = "Session expired. Please start over."
            return
        }
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            val result = authRepository.verifyPin(requestId, pin)

            _isLoading.value = false

            result.onSuccess { response ->
                if (response.success && response.verified == true) {
                    _pinVerified.value = true
                } else {
                    _errorMessage.value = response.message ?: "Invalid PIN"
                }
            }.onFailure { exception ->
                _errorMessage.value = exception.message ?: "Failed to verify PIN."
            }
        }
    }

    /** Step 3 — set new password */
    fun resetPassword(newPassword: String) {
        if (requestId == -1) {
            _errorMessage.value = "Session expired. Please start over."
            return
        }
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            val result = authRepository.resetPassword(requestId, newPassword)

            _isLoading.value = false

            result.onSuccess { response ->
                if (response.success) {
                    _passwordReset.value = true
                } else {
                    _errorMessage.value = response.message ?: "Failed to reset password"
                }
            }.onFailure { exception ->
                _errorMessage.value = exception.message ?: "Failed to reset password. Please try again."
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