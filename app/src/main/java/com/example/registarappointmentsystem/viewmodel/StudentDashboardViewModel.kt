package com.example.registarappointmentsystem.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.registarappointmentsystem.core.UiResult
import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AppointmentRepository
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl

import kotlinx.coroutines.launch

class StudentDashboardViewModel(
    private val appointmentRepository: AppointmentRepository
) : ViewModel() {

    private val _appointments = MutableLiveData(UiResult<List<Appointment>>(isLoading = true))
    val appointments: LiveData<UiResult<List<Appointment>>> = _appointments

    fun loadAppointments(userId: Int) {
        _appointments.value = UiResult(isLoading = true)
        viewModelScope.launch {
            val list = appointmentRepository.getAppointmentsForUser(userId)
            _appointments.value = UiResult(data = list)
        }
    }
}

class StudentDashboardViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StudentDashboardViewModel::class.java)) {
            val repo = AppointmentRepositoryImpl(RetrofitClient.apiService)
            return StudentDashboardViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
