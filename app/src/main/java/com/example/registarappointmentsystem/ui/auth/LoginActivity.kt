package com.example.registarappointmentsystem.ui.auth

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import com.example.registarappointmentsystem.databinding.ActivityLoginBinding
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.viewmodel.AuthViewModel
import com.example.registarappointmentsystem.viewmodel.AuthViewModelFactory
import com.google.android.material.snackbar.Snackbar
import com.example.registarappointmentsystem.utils.SessionManager
import com.example.registarappointmentsystem.utils.NotificationHelper
import com.example.registarappointmentsystem.utils.BackgroundSyncScheduler

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
    private lateinit var sessionManager: SessionManager
    private lateinit var notificationHelper: NotificationHelper
    
    // Permission request launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result will be handled automatically by NotificationManagerCompat
        // which catches SecurityException if permission not granted
    }

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionManager = SessionManager(this)
        notificationHelper = NotificationHelper(this)

        // Ensure Android 13+ can actually show push notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

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

        // Create account navigation with animation
        binding.textCreateAccount.setOnClickListener {
            animateTextClick(it)
            binding.root.postDelayed({
                startActivity(Intent(this, RegisterActivity::class.java))
            }, 150)
        }

        // Forgot password with animation
        binding.textForgotPassword.setOnClickListener {
            animateTextClick(it)
            binding.root.postDelayed({
                startActivity(Intent(this, ForgotPasswordActivity::class.java))
            }, 150)
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
                
                // Initialize session timeout on successful login
                sessionManager.updateLastActivityTime()
                
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
                    putBoolean("must_change_password", user.must_change_password == true)
                    // Save profile fields so dashboard can show incomplete banner
                    putString("user_contact_number", user.contact_number ?: "")
                    putString("user_gender", user.gender ?: "")
                    putString("user_birthday", user.birthday ?: "")
                    putString("user_address", user.address ?: "")
                    apply()
                }

                BackgroundSyncScheduler.start(this)

                // Determine if profile is incomplete
                val mustChangePassword = user.must_change_password == true
                val isProfileIncomplete = user.contact_number.isNullOrBlank() ||
                    user.gender.isNullOrBlank() ||
                    user.birthday.isNullOrBlank() ||
                    user.address.isNullOrBlank()

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
                    if (mustChangePassword) {
                        val intent = Intent(this, com.example.registarappointmentsystem.ui.profile.ProfileActivity::class.java).apply {
                            putExtra("forcePasswordChange", true)
                        }
                        startActivity(intent)
                    } else if (isProfileIncomplete) {
                        val intent = Intent(this, com.example.registarappointmentsystem.ui.profile.ProfileActivity::class.java).apply {
                            putExtra("isProfileIncomplete", true)
                        }
                        startActivity(intent)
                    } else {
                        startActivity(Intent(this, StudentDashboardActivity::class.java))
                    }
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
        // Staggered entry animations with smooth curves
        val views = listOf(
            binding.viewAvatarCircle,
            binding.textTitle,
            binding.cardForm
        )
        
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f
            
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(600)
                .setStartDelay(index * 120L)
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    private fun animateTextClick(view: View) {
        view.animate()
            .alpha(0.7f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .alpha(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
}
