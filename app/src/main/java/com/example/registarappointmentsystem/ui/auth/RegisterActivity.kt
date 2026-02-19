package com.example.registarappointmentsystem.ui.auth

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.registarappointmentsystem.databinding.ActivityRegisterBinding
import com.example.registarappointmentsystem.viewmodel.AuthViewModel
import com.example.registarappointmentsystem.viewmodel.AuthViewModelFactory

/**
 * Simple guest registration screen matching the login style.
 * For now this focuses on UI; backend wiring can be added later.
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
    }

    private fun setupUi() {
        binding.buttonSignUp.setOnClickListener {
            // TODO: hook into ViewModel register logic when backend-ready
        }

        binding.textGoToLogin.setOnClickListener {
            finish()
        }
    }
}