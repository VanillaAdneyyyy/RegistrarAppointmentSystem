package com.example.registarappointmentsystem.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.example.registarappointmentsystem.databinding.ActivityLoginBinding
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.viewmodel.AuthViewModel
import com.example.registarappointmentsystem.viewmodel.AuthViewModelFactory

/**
 * Login screen UI. Backend-ready MVVM:
 * - Uses AuthViewModel
 * - Observes LiveData for form state and login result
 * - Currently works with a fake AuthRepository implementation.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        observeViewModel()
    }

    private fun setupUi() {
        binding.buttonLogin.setOnClickListener {
           
            startActivity(Intent(this, StudentDashboardActivity::class.java))
            finish()

            // If you want to re-enable real validation + fake backend
          
        }

        binding.textCreateAccount.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.loginFormState.observe(this, Observer { state ->
            // For now, keep the Log In button always enabled
            // so you can easily reach the dashboard.
            // binding.buttonLogin.isEnabled = state.isValid
            binding.textInputEmail.error = state.emailError
            binding.textInputPassword.error = state.passwordError
        })

        viewModel.loginResult.observe(this, Observer { result ->
            // For now, keep this minimal: you can replace with navigation or toasts later.
            binding.progressBar.visibility = if (result.isLoading) View.VISIBLE else View.GONE

            // Optional: Display error or navigate on success.
            if (result.data != null) {
                // Navigate to student dashboard for now.
                startActivity(Intent(this, StudentDashboardActivity::class.java))
                finish()
            } else {
                binding.textError.apply {
                    text = result.errorMessage.orEmpty()
                    visibility = if (result.errorMessage != null) View.VISIBLE else View.GONE
                }
            }
        })
    }
}
