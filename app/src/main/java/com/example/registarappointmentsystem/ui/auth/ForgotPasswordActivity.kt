package com.example.registarappointmentsystem.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, maxOf(imeBottom, sysBarBottom))
            insets
        }
        viewModel = ViewModelProvider(this, ForgotPasswordViewModelFactory())[ForgotPasswordViewModel::class.java]

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.buttonBack.setOnClickListener { finish() }

        // Step 1 — send PIN to email
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
            binding.textInputEmail.error = null
            viewModel.requestPasswordReset(email)
        }

        // Step 2 — verify PIN
        binding.buttonVerifyPin.setOnClickListener {
            val pin = binding.editTextPinReset.text.toString().trim()
            if (pin.length != 6) {
                binding.textViewPinError.text = "Please enter the 6-digit PIN"
                binding.textViewPinError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            binding.textViewPinError.visibility = View.GONE
            viewModel.verifyPin(pin)
        }

        // Step 3 — reset password
        binding.buttonResetPassword.setOnClickListener {
            val newPassword = binding.editTextNewPassword.text.toString().trim()
            val confirmPassword = binding.editTextConfirmNewPassword.text.toString().trim()
            when {
                newPassword.isEmpty() -> {
                    binding.textInputNewPassword.error = "Password is required"
                    return@setOnClickListener
                }
                newPassword.length < 6 -> {
                    binding.textInputNewPassword.error = "At least 6 characters"
                    return@setOnClickListener
                }
                newPassword != confirmPassword -> {
                    binding.textInputConfirmNewPassword.error = "Passwords do not match"
                    return@setOnClickListener
                }
            }
            binding.textInputNewPassword.error = null
            binding.textInputConfirmNewPassword.error = null
            viewModel.resetPassword(newPassword)
        }

        binding.textViewLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.buttonSendReset.isEnabled = !isLoading
            binding.buttonVerifyPin.isEnabled = !isLoading
            binding.buttonResetPassword.isEnabled = !isLoading
        }

        // Step 1 success → show PIN section
        viewModel.resetSent.observe(this) { sent ->
            if (sent) {
                val email = binding.editTextEmail.text.toString().trim()
                binding.textViewSuccess.text = "PIN sent to $email. Check your inbox."
                binding.textViewSuccess.visibility = View.VISIBLE
                binding.textViewError.visibility = View.GONE
                binding.textInputEmail.isEnabled = false
                binding.buttonSendReset.isEnabled = false
                binding.layoutPinReset.visibility = View.VISIBLE
                binding.textViewDescription.text = "Enter the PIN we just sent to your email"
            }
        }

        // Step 2 success → show new password section
        viewModel.pinVerified.observe(this) { verified ->
            if (verified) {
                binding.layoutPinReset.visibility = View.GONE
                binding.textViewSuccess.text = "PIN verified! Please set your new password."
                binding.textViewSuccess.visibility = View.VISIBLE
                binding.layoutNewPassword.visibility = View.VISIBLE
                binding.textViewDescription.text = "Almost done! Enter your new password below."
            }
        }

        // Step 3 success → show success + navigate to Login
        viewModel.passwordReset.observe(this) { reset ->
            if (reset) {
                binding.layoutNewPassword.visibility = View.GONE
                binding.textViewSuccess.text = "Password reset successfully! Redirecting to login..."
                binding.textViewSuccess.visibility = View.VISIBLE
                binding.textViewError.visibility = View.GONE
                binding.root.postDelayed({
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }, 2000)
            }
        }

        // Error observer
        viewModel.errorMessage.observe(this) { error ->
            error?.let {
                // Show error in PIN section if it's visible, otherwise in main error view
                if (binding.layoutPinReset.visibility == View.VISIBLE) {
                    binding.textViewPinError.text = it
                    binding.textViewPinError.visibility = View.VISIBLE
                } else {
                    binding.textViewError.text = it
                    binding.textViewError.visibility = View.VISIBLE
                    binding.textViewSuccess.visibility = View.GONE
                }
            }
        }
    }
}
