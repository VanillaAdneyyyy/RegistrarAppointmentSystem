package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.registarappointmentsystem.core.UiResult
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AppointmentRepository
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl
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

    fun onSubmitClicked(
        username: String,
        purpose: String,
        reason: String,
        contactNumber: String,
        userId: Int = -1,
        documentTypeIds: List<Int> = emptyList(),
        studentIdNumber: String? = null
    ) {
        val typeError = if (documentTypeIds.isEmpty() && purpose.isBlank()) "Please select at least one document" else null
        val reasonError = if (reason.isBlank()) "Please explain the purpose of your request" else null

        val valid = typeError == null && reasonError == null

        _formState.value = RequestCredentialsFormState(
            credentialType = purpose,
            reason = reason,
            credentialTypeError = typeError,
            reasonError = reasonError,
            isValid = valid
        )

        if (!valid) return

        _submitResult.value = UiResult(isLoading = true)

        viewModelScope.launch {
            val result = appointmentRepository.requestCredentials(
                username = username,
                credentialType = purpose,
                reason = reason,
                contactNumber = contactNumber,
                userId = userId,
                documentTypeIds = documentTypeIds,
                studentIdNumber = studentIdNumber
            )
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
            val repo = AppointmentRepositoryImpl(RetrofitClient.apiService)
            return RequestCredentialsViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
