package com.example.registarappointmentsystem.ui.auth

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.Role
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AuthRepositoryImpl
import com.example.registarappointmentsystem.databinding.ActivityRegisterBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Enhanced Registration screen with:
 * - Name validation (letters, spaces, periods, hyphens only)
 * - Email PIN verification for guest registration
 * - Form validation with toast notifications
 * - Animated transitions
 * - Real API integration
 * - Loading states
 * - Error handling with Snackbar
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private var selectedRole: Role = Role.GUEST
    private lateinit var authRepository: AuthRepositoryImpl
    
    // Email verification state
    private var isEmailVerified = false
    private var verificationEmail = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        authRepository = AuthRepositoryImpl(RetrofitClient.apiService)

        setupUi()
        animateEntry()
    }

    private fun setupUi() {
        // Hide role selection - only GUEST can register
        binding.layoutSegment.visibility = android.view.View.GONE
        
        // Force GUEST role
        selectedRole = Role.GUEST

        // Sign up button
        binding.buttonSignUp.setOnClickListener {
            animateButtonPress(it)
            attemptRegistration()
        }

        // Go to login
        binding.textGoToLogin.setOnClickListener {
            animateTextClick(it)
            finish()
        }
        
        // Email verification button
        binding.buttonVerifyEmail.setOnClickListener {
            requestEmailVerificationPin()
        }
        
        // PIN confirmation button
        binding.buttonConfirmPin.setOnClickListener {
            verifyEmailPin()
        }
    }
    
    // Name validation function
    private fun validateName(name: String, fieldName: String): Pair<Boolean, String> {
        if (name.isEmpty()) {
            return if (fieldName == "First name" || fieldName == "Last name") {
                Pair(false, "$fieldName is required")
            } else {
                Pair(true, "") // Optional fields can be empty
            }
        }
        
        val validNameRegex = Regex("^[a-zA-Z\\s\\.\\-]+$")
        if (!validNameRegex.matches(name)) {
            return Pair(false, "$fieldName can only contain letters, spaces, periods (.), and hyphens (-)")
        }
        
        return Pair(true, "")
    }
    
    private fun requestEmailVerificationPin() {
        val email = binding.editTextEmail.text.toString().trim()
        val firstName = binding.editTextFirstName.text.toString().trim()
        
        if (email.isEmpty()) {
            showError("Please enter your email first")
            return
        }
        
        if (firstName.isEmpty()) {
            showError("Please enter your first name")
            return
        }
        
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Please enter a valid email address")
            return
        }
        
        showLoading(true)
        
        lifecycleScope.launch {
            try {
                val request = mapOf("email" to email, "first_name" to firstName)
                val response = authRepository.requestEmailVerificationPin(request)
                
                showLoading(false)
                
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.get("success") == true) {
                        verificationEmail = email
                        binding.layoutPinSection.visibility = android.view.View.VISIBLE
                        binding.textEmailStatus.visibility = android.view.View.VISIBLE
                        binding.textEmailStatus.text = "PIN sent! Check your email."
                        binding.textEmailStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                        binding.buttonVerifyEmail.text = "Resend"
                        showToast("PIN sent to your email!")
                    } else {
                        showError(body?.get("message") as? String ?: "Failed to send PIN")
                    }
                } else {
                    showError("Failed to send PIN. Please try again.")
                }
            } catch (e: Exception) {
                showLoading(false)
                showError("Error: ${e.message}")
            }
        }
    }
    
    private fun verifyEmailPin() {
        val pin = binding.editTextPin.text.toString().trim()
        val email = binding.editTextEmail.text.toString().trim()
        
        if (pin.length != 6) {
            binding.textPinError.visibility = android.view.View.VISIBLE
            binding.textPinError.text = "Please enter 6-digit PIN"
            return
        }
        
        showLoading(true)
        
        lifecycleScope.launch {
            try {
                val request = mapOf("email" to email, "pin" to pin)
                val response = authRepository.verifyEmailPin(request)
                
                showLoading(false)
                
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.get("success") == true && body["verified"] == true) {
                        isEmailVerified = true
                        binding.layoutPinSection.visibility = android.view.View.GONE
                        binding.textEmailStatus.text = "✓ Email verified"
                        binding.textEmailStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                        binding.editTextEmail.isEnabled = false
                        binding.buttonVerifyEmail.isEnabled = false
                        binding.buttonVerifyEmail.text = "Verified"
                        showToast("Email verified successfully!")
                    } else {
                        binding.textPinError.visibility = android.view.View.VISIBLE
                        binding.textPinError.text = body?.get("message") as? String ?: "Invalid PIN"
                    }
                } else {
                    binding.textPinError.visibility = android.view.View.VISIBLE
                    binding.textPinError.text = "Failed to verify PIN"
                }
            } catch (e: Exception) {
                showLoading(false)
                binding.textPinError.visibility = android.view.View.VISIBLE
                binding.textPinError.text = "Error: ${e.message}"
            }
        }
    }
    
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun attemptRegistration() {
        val firstName = binding.editTextFirstName.text.toString().trim()
        val middleName = binding.editTextMiddleName.text.toString().trim()
        val lastName = binding.editTextLastName.text.toString().trim()
        val extensionName = binding.editTextExtensionName.text.toString().trim()
        val email = binding.editTextEmail.text.toString().trim()
        val password = binding.editTextPassword.text.toString().trim()
        val confirmPassword = binding.editTextConfirmPassword.text.toString().trim()
        val idNumber = if (selectedRole == Role.STUDENT) {
            binding.editTextIdNumber.text.toString().trim()
        } else null

        // Name validation
        val firstNameValidation = validateName(firstName, "First name")
        if (!firstNameValidation.first) {
            showError(firstNameValidation.second)
            return
        }
        
        val middleNameValidation = validateName(middleName, "Middle name")
        if (!middleNameValidation.first) {
            showError(middleNameValidation.second)
            return
        }
        
        val lastNameValidation = validateName(lastName, "Last name")
        if (!lastNameValidation.first) {
            showError(lastNameValidation.second)
            return
        }
        
        val extensionNameValidation = validateName(extensionName, "Extension name")
        if (!extensionNameValidation.first) {
            showError(extensionNameValidation.second)
            return
        }

        // Validation
        when {
            firstName.isEmpty() -> showError("First name is required")
            lastName.isEmpty() -> showError("Last name is required")
            email.isEmpty() -> showError("Email is required")
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> showError("Invalid email format")
            !isEmailVerified -> showError("Please verify your email before registering")
            password.isEmpty() -> showError("Password is required")
            password.length < 6 -> showError("Password must be at least 6 characters")
            password != confirmPassword -> showError("Passwords do not match")
            else -> performRegistration(firstName, middleName, lastName, extensionName, email, password, idNumber)
        }
    }

    private fun performRegistration(
        firstName: String,
        middleName: String,
        lastName: String,
        extensionName: String,
        email: String,
        password: String,
        idNumber: String?
    ) {
        showLoading(true)

        lifecycleScope.launch {
            try {
                val result = authRepository.register(
                    firstName = firstName,
                    middleName = middleName.ifEmpty { null },
                    lastName = lastName,
                    extensionName = extensionName.ifEmpty { null },
                    email = email,
                    password = password,
                    role = selectedRole,
                    idNumber = idNumber,
                    employeeNumber = null
                )

                result.fold(
                    onSuccess = { (success, message) ->
                        showLoading(false)
                        if (success) {
                            Snackbar.make(binding.root, "Registration successful! Please login.", Snackbar.LENGTH_LONG)
                                .setAction("LOGIN") {
                                    finish()
                                }
                                .show()
                        } else {
                            showError(message)
                        }
                    },
                    onFailure = { exception ->
                        showLoading(false)
                        showError("Registration failed: ${exception.message}")
                    }
                )
            } catch (e: Exception) {
                showLoading(false)
                showError("Error: ${e.message}")
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.buttonSignUp.isEnabled = !isLoading
        binding.buttonSignUp.text = if (isLoading) "" else "Sign Up"
    }

    private fun showError(message: String) {
        binding.textError.apply {
            text = message
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(300).start()
        }

        // Shake animation
        ObjectAnimator.ofFloat(binding.cardForm, "translationX", 0f, 20f, -20f, 20f, -20f, 0f).apply {
            duration = 400
            start()
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun animateEntry() {
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
