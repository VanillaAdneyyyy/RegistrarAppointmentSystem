package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AuthRepositoryImpl

class AuthViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            // Use real API repository instead of fake
            val apiService = RetrofitClient.apiService
            val repository = AuthRepositoryImpl(apiService)
            return AuthViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
