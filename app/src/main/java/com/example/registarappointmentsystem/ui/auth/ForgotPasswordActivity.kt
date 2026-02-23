package com.example.registarappointmentsystem.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.registarappointmentsystem.databinding.ActivityForgotPasswordBinding
import com.example.registarappointmentsystem.viewmodel.ForgotPasswordViewModel
import com.example.registarappointmentsystem.viewmodel.ForgotPasswordViewModelFactory

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private lateinit var viewModel: ForgotPasswordViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, ForgotPasswordViewModelFactory())[ForgotPasswordViewModel::class.java]

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        // Back button
        binding.buttonBack.setOnClickListener {
            finish()
        }

        // Send Reset Link button - simplified email only flow
        binding.buttonSendReset.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()

            if (email.isEmpty()) {
                binding.textInputEmail.error = "Email is required"
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.textInputEmail.error = "Please enter a valid email"
                return@setOnClickListener
            }

            viewModel.requestPasswordReset(email)
        }

        // Login link
        binding.textViewLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun observeViewModel() {
        // Observe loading state
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonSendReset.isEnabled = !isLoading
        }

        // Observe success state
        viewModel.resetSent.observe(this) { success ->
            if (success) {
                binding.textViewSuccess.visibility = View.VISIBLE
                binding.textViewError.visibility = View.GONE
                binding.buttonSendReset.text = "Sent!"
                binding.buttonSendReset.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    resources.getColor(com.example.registarappointmentsystem.R.color.success_green, theme)
                )
            }
        }

        // Observe error state
        viewModel.errorMessage.observe(this) { error ->
            error?.let {
                binding.textViewError.text = it
                binding.textViewError.visibility = View.VISIBLE
                binding.textViewSuccess.visibility = View.GONE
            }
        }
    }
}
