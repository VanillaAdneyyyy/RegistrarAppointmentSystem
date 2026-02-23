package com.example.registarappointmentsystem.ui.auth

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.lifecycle.Observer
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.Role
import com.example.registarappointmentsystem.databinding.ActivityLoginBinding
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.viewmodel.AuthViewModel
import com.example.registarappointmentsystem.viewmodel.AuthViewModelFactory
import com.google.android.material.snackbar.Snackbar

/**
 * Enhanced Login screen UI with PostgreSQL backend integration.
 * Features:
 * - Role selection (Student/Guest) with animated toggle
 * - Real API authentication
 * - Loading states with progress indicator
 * - Error handling with Snackbar
 * - Smooth animations
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var selectedRole: Role = Role.STUDENT

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        observeViewModel()
        animateEntry()
    }

    private fun setupUi() {
        // Role selection toggle
        binding.buttonStudent.setOnClickListener {
            selectRole(Role.STUDENT)
        }
        
        binding.buttonGuest.setOnClickListener {
            selectRole(Role.GUEST)
        }

        // Login button with real API call
        binding.buttonLogin.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()
            
            // Animate button click
            animateButtonPress(binding.buttonLogin)
            
            // Call ViewModel login
            viewModel.onLoginClicked(email, password)
        }

        // Create account navigation
        binding.textCreateAccount.setOnClickListener {
            animateTextClick(it)
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Forgot password
        binding.textForgotPassword.setOnClickListener {
            animateTextClick(it)
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

    }

    private fun selectRole(role: Role) {
        selectedRole = role
        
        // Animate role selection
        when (role) {
            Role.STUDENT -> {
                updateRoleButtonState(binding.buttonStudent, true)
                updateRoleButtonState(binding.buttonGuest, false)
                binding.editTextEmail.hint = "Student ID or Email"
            }
            Role.GUEST -> {
                updateRoleButtonState(binding.buttonStudent, false)
                updateRoleButtonState(binding.buttonGuest, true)
                binding.editTextEmail.hint = "Email Address"
            }
            else -> {}
        }
        
        // Animate the card
        ObjectAnimator.ofFloat(binding.cardForm, "translationY", 0f, -20f, 0f).apply {
            duration = 300
            interpolator = OvershootInterpolator()
            start()
        }
    }

    private fun updateRoleButtonState(button: View, isSelected: Boolean) {
        if (isSelected) {
            button.setBackgroundColor(getColor(R.color.segment_selected))
            (button as android.widget.Button).setTextColor(getColor(android.R.color.white))
        } else {
            button.setBackgroundColor(getColor(R.color.segment_background))
            (button as android.widget.Button).setTextColor(getColor(R.color.segment_unselected_text))
        }
    }

    private fun observeViewModel() {
        viewModel.loginFormState.observe(this, Observer { state ->
            binding.buttonLogin.isEnabled = state.isValid
            binding.textInputEmail.error = state.emailError
            binding.textInputPassword.error = state.passwordError
        })

        viewModel.loginResult.observe(this, Observer { result ->
            // Loading state
            binding.progressBar.visibility = if (result.isLoading) View.VISIBLE else View.GONE
            binding.buttonLogin.isEnabled = !result.isLoading
            
            // Success
            if (result.data != null) {
                val user = result.data
                Snackbar.make(binding.root, "Welcome ${user.getFullName()}!", Snackbar.LENGTH_SHORT).show()
                
                // Navigate with animation
                binding.root.postDelayed({
                    startActivity(Intent(this, StudentDashboardActivity::class.java))
                    finish()
                }, 500)
            }
            
            // Error
            result.errorMessage?.let { error ->
                binding.textError.apply {
                    text = error
                    visibility = View.VISIBLE
                    alpha = 0f
                    animate().alpha(1f).setDuration(300).start()
                }
                
                // Shake animation for error
                ObjectAnimator.ofFloat(binding.cardForm, "translationX", 0f, 20f, -20f, 20f, -20f, 0f).apply {
                    duration = 400
                    start()
                }
            }
        })
    }

    private fun animateEntry() {
        // Staggered entry animations
        val views = listOf(
            binding.viewAvatarCircle,
            binding.textTitle,
            binding.layoutSegment,
            binding.cardForm
        )
        
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 50f
            
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay(index * 100L)
                .setInterpolator(OvershootInterpolator())
                .start()
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun animateTextClick(view: View) {
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
}
