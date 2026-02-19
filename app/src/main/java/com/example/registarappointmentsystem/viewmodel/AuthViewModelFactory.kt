package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.registarappointmentsystem.data.fake.FakeAuthRepository

class AuthViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            return AuthViewModel(FakeAuthRepository()) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}