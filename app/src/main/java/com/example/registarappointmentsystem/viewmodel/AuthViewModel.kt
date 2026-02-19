package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.registarappointmentsystem.core.UiResult
import com.example.registarappointmentsystem.data.model.User
import com.example.registarappointmentsystem.data.repository.AuthRepository
import kotlinx.coroutines.launch

data class LoginFormState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val isValid: Boolean = false
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _loginFormState = MutableLiveData(LoginFormState())
    val loginFormState: LiveData<LoginFormState> = _loginFormState

    private val _loginResult = MutableLiveData(UiResult<User>())
    val loginResult: LiveData<UiResult<User>> = _loginResult

    fun onLoginClicked(email: String, password: String) {
        val emailError = if (email.isBlank()) "Email is required" else null
        val passwordError = if (password.isBlank()) "Password is required" else null

        val isValid = emailError == null && passwordError == null
        _loginFormState.value = LoginFormState(
            email = email,
            password = password,
            emailError = emailError,
            passwordError = passwordError,
            isValid = isValid
        )

        if (!isValid) return

        _loginResult.value = UiResult(isLoading = true)

        viewModelScope.launch {
            val result = authRepository.login(email, password)
            _loginResult.value = when {
                result.isSuccess -> UiResult(data = result.getOrNull())
                else -> UiResult(errorMessage = result.exceptionOrNull()?.message ?: "Login failed")
            }
        }
    }
}

