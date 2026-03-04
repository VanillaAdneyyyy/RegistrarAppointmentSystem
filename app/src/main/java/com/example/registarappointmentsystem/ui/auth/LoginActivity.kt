package com.example.registarappointmentsystem.ui.auth

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import com.example.registarappointmentsystem.databinding.ActivityLoginBinding
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.viewmodel.AuthViewModel
import com.example.registarappointmentsystem.viewmodel.AuthViewModelFactory
import com.google.android.material.snackbar.Snackbar

/**
 * Enhanced Login screen UI with PostgreSQL backend integration.
 * Features:
 * - Auto-detects role based on input (email = guest, id_number = student)
 * - Real API authentication
 * - Loading states with progress indicator
 * - Error handling with Snackbar
 * - Smooth animations
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, maxOf(imeBottom, sysBarBottom))
            insets
        }
        setupUi()
        observeViewModel()
        animateEntry()
        loadRememberedCredentials()
    }

    private fun loadRememberedCredentials() {
        val prefs = getSharedPreferences("remember_me", MODE_PRIVATE)
        if (prefs.getBoolean("enabled", false)) {
            binding.editTextEmail.setText(prefs.getString("identifier", ""))
            binding.editTextPassword.setText(prefs.getString("password", ""))
            binding.checkBoxRememberMe.isChecked = true
        }
    }

    private fun saveRememberedCredentials(identifier: String, password: String) {
        val prefs = getSharedPreferences("remember_me", MODE_PRIVATE).edit()
        if (binding.checkBoxRememberMe.isChecked) {
            prefs.putBoolean("enabled", true)
            prefs.putString("identifier", identifier)
            prefs.putString("password", password)
        } else {
            prefs.clear()
        }
        prefs.apply()
    }

    private fun setupUi() {
        // Login button with real API call
        binding.buttonLogin.setOnClickListener {
            val identifier = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()
            
            // Animate button click
            animateButtonPress(binding.buttonLogin)
            
            // Call ViewModel login - role is auto-detected based on input
            viewModel.onLoginClicked(identifier, password)
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
                android.util.Log.d("LoginActivity", "Login successful for user: ${user.email}")
                Snackbar.make(binding.root, "Welcome ${user.getFullName()}!", Snackbar.LENGTH_SHORT).show()
                
                // Save user info to SharedPreferences
                val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
                val savedEmail = user.email
                val userId = user.id
                val firstName = user.first_name?.ifBlank { null }
                    ?: user.getFullName().split(" ").firstOrNull()
                    ?: savedEmail?.substringBefore("@")
                    ?: "Guest"
                // Full name for use as appointment username (matches web app format)
                val fullName = listOfNotNull(
                    user.first_name?.trim()?.ifBlank { null },
                    user.middle_name?.trim()?.ifBlank { null },
                    user.last_name?.trim()?.ifBlank { null },
                    user.extension_name?.trim()?.ifBlank { null }
                ).joinToString(" ").ifBlank { savedEmail ?: "Guest" }
                sharedPrefs.edit().apply {
                    putString("user_email", savedEmail)
                    putInt("user_id", userId)
                    putString("user_first_name", firstName)
                    putString("user_full_name", fullName)
                    putString("user_role", user.role ?: "guest")
                    apply()
                }

                // Persist or clear remembered credentials
                val identifier = binding.editTextEmail.text.toString().trim()
                val password = binding.editTextPassword.text.toString().trim()
                saveRememberedCredentials(identifier, password)
                android.util.Log.d("LoginActivity", "Saved email: $savedEmail, userId: $userId to SharedPreferences")
                
                // Verify it was saved
                val verifyEmail = sharedPrefs.getString("user_email", null)
                val verifyUserId = sharedPrefs.getInt("user_id", -1)
                android.util.Log.d("LoginActivity", "Verified - email: $verifyEmail, userId: $verifyUserId")

                
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
