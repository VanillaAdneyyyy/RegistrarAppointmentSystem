package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.registarappointmentsystem.core.UiResult
import com.example.registarappointmentsystem.data.fake.FakeAppointmentRepository
import com.example.registarappointmentsystem.data.repository.AppointmentRepository
import kotlinx.coroutines.launch

data class RequestCredentialsFormState(
    val credentialType: String = "",
    val reason: String = "",
    val credentialTypeError: String? = null,
    val reasonError: String? = null,
    val isValid: Boolean = false
)

class RequestCredentialsViewModel(
    private val appointmentRepository: AppointmentRepository
) : ViewModel() {

    private val _formState = MutableLiveData(RequestCredentialsFormState())
    val formState: LiveData<RequestCredentialsFormState> = _formState

    private val _submitResult = MutableLiveData(UiResult<Unit>())
    val submitResult: LiveData<UiResult<Unit>> = _submitResult

    fun onSubmitClicked(credentialType: String, reason: String) {
        val typeError = if (credentialType.isBlank()) "Credential type is required" else null
        val reasonError = if (reason.isBlank()) "Reason is required" else null

        val valid = typeError == null && reasonError == null

        _formState.value = RequestCredentialsFormState(
            credentialType = credentialType,
            reason = reason,
            credentialTypeError = typeError,
            reasonError = reasonError,
            isValid = valid
        )

        if (!valid) return

        _submitResult.value = UiResult(isLoading = true)

        viewModelScope.launch {
            val result = appointmentRepository.requestCredentials(credentialType, reason)
            _submitResult.value = if (result.isSuccess) {
                UiResult(data = Unit)
            } else {
                UiResult(errorMessage = result.exceptionOrNull()?.message ?: "Request failed")
            }
        }
    }
}

class RequestCredentialsViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RequestCredentialsViewModel::class.java)) {
            val repo = FakeAppointmentRepository()
            return RequestCredentialsViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

