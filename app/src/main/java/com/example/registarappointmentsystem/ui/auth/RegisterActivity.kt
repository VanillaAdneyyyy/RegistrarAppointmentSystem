package com.example.registarappointmentsystem.ui.auth

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.text.InputFilter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.Role
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AuthRepositoryImpl
import com.example.registarappointmentsystem.databinding.ActivityRegisterBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Registration screen — Guest only.
 * Flow:
 *  1. User fills in all details and clicks "Create Account"
 *  2. System validates fields and sends a 6-digit PIN to the entered email
 *  3. PIN section appears; user enters PIN and clicks "Verify & Register"
 *  4. PIN verified → account created → navigate to Login
 */
class RegisterActivity : AppCompatActivity() {

    companion object {
        private const val NAME_MIN_LENGTH = 3
        private const val NAME_MAX_LENGTH = 50
        private const val EXTENSION_MIN_LENGTH = 3
        private const val EXTENSION_MAX_LENGTH = 5
    }

    private lateinit var binding: ActivityRegisterBinding
    private val selectedRole: Role = Role.GUEST
    private lateinit var authRepository: AuthRepositoryImpl

    /** True once the PIN has been sent to the user's email */
    private var isPinSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        authRepository = AuthRepositoryImpl(RetrofitClient.apiService)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sysBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            // Keep only navigation-bar inset; keyboard height is handled by adjustResize.
            v.setPadding(0, 0, 0, sysBarBottom)
            insets
        }
        setupUi()
        animateEntry()
    }

    private fun setupUi() {
        binding.editTextFirstName.filters = arrayOf(InputFilter.LengthFilter(NAME_MAX_LENGTH))
        binding.editTextMiddleName.filters = arrayOf(InputFilter.LengthFilter(NAME_MAX_LENGTH))
        binding.editTextLastName.filters = arrayOf(InputFilter.LengthFilter(NAME_MAX_LENGTH))
        binding.editTextExtensionName.filters = arrayOf(InputFilter.LengthFilter(EXTENSION_MAX_LENGTH))

        // Role segment always hidden — only GUEST registration
        binding.layoutSegment.visibility = View.GONE

        // "Create Account" button — Step 1: validate form and send PIN
        binding.buttonSignUp.setOnClickListener {
            animateButtonPress(it)
            if (!isPinSent) {
                attemptSendPin()
            } else {
                // Already in PIN step, scroll to PIN section to remind user
                binding.layoutPinSection.visibility = View.VISIBLE
                showToast("Enter the PIN sent to your email")
            }
        }

        // "Verify & Register" button — Step 2: verify PIN then register
        binding.buttonConfirmPin.setOnClickListener {
            verifyPinAndRegister()
        }

        binding.textGoToLogin.setOnClickListener {
            animateTextClick(it)
            finish()
        }

        // Password strength indicator
        binding.editTextPassword.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePasswordStrength(s?.toString() ?: "") }
        })
    }

    // ===== Name validation =====
    private fun validateName(
        name: String,
        fieldName: String,
        required: Boolean,
        minLength: Int,
        maxLength: Int
    ): Pair<Boolean, String> {
        if (name.isEmpty()) {
            return if (required) Pair(false, "$fieldName is required") else Pair(true, "")
        }

        if (name.length < minLength || name.length > maxLength) {
            return Pair(false, "$fieldName must be $minLength-$maxLength characters")
        }

        val validNameRegex = Regex("^[a-zA-Z\\s\\.\\-]+$")
        if (!validNameRegex.matches(name))
            return Pair(false, "$fieldName can only contain letters, spaces, periods (.), and hyphens (-)")
        return Pair(true, "")
    }

    // ===== Step 1: validate → send PIN =====
    private fun attemptSendPin() {
        val firstName    = binding.editTextFirstName.text.toString().trim()
        val middleName   = binding.editTextMiddleName.text.toString().trim()
        val lastName     = binding.editTextLastName.text.toString().trim()
        val extensionN   = binding.editTextExtensionName.text.toString().trim()
        val email        = binding.editTextEmail.text.toString().trim()
        val password     = binding.editTextPassword.text.toString().trim()
        val confirmPw    = binding.editTextConfirmPassword.text.toString().trim()

        // Clear all field errors
        binding.textInputFirstName.error = null
        binding.textInputMiddleName.error = null
        binding.textInputLastName.error = null
        binding.textInputExtensionName.error = null
        binding.textInputEmail.error = null
        binding.textInputPassword.error = null
        binding.textInputConfirmPassword.error = null

        // Name validation
        for ((value, label, inputLayout) in listOf(
            Triple(firstName, Triple("First name", true, NAME_MAX_LENGTH), binding.textInputFirstName),
            Triple(middleName, Triple("Middle name", false, NAME_MAX_LENGTH), binding.textInputMiddleName),
            Triple(lastName, Triple("Last name", true, NAME_MAX_LENGTH), binding.textInputLastName),
            Triple(extensionN, Triple("Extension name", false, EXTENSION_MAX_LENGTH), binding.textInputExtensionName)
        )) {
            val (fieldName, required, maxLength) = label
            val minLength = if (fieldName == "Extension name") EXTENSION_MIN_LENGTH else NAME_MIN_LENGTH
            val (ok, msg) = validateName(value, fieldName, required, minLength, maxLength)
            if (!ok) { inputLayout.error = msg; showError(msg); return }
        }

        // Field validation
        when {
            email.isEmpty()      -> { binding.textInputEmail.error = "Email is required"; showError("Email is required"); return }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.textInputEmail.error = "Invalid email format"; showError("Invalid email format"); return }
            password.isEmpty()   -> { binding.textInputPassword.error = "Password is required"; showError("Password is required"); return }
            password.length < 8  -> { binding.textInputPassword.error = "Password must be at least 8 characters"; showError("Password must be at least 8 characters"); return }
            !Regex("^(?=.*[A-Za-z])(?=.*\\d)\\S+$").matches(password) -> {
                binding.textInputPassword.error = "Password must include letters and numbers (special characters allowed)"; showError("Password must include letters and numbers (special characters allowed)"); return }
            password != confirmPw -> { binding.textInputConfirmPassword.error = "Passwords do not match"; showError("Passwords do not match"); return }
        }

        binding.textError.visibility = View.GONE
        sendRegistrationPin(email, firstName)
    }

    private fun sendRegistrationPin(email: String, firstName: String) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val request = mapOf("email" to email, "first_name" to firstName)
                val response = authRepository.requestEmailVerificationPin(request)
                showLoading(false)
                if (response.isSuccessful) {
                    val body = response.body()
                    val success = body?.get("success") == true
                    val message = body?.get("message") as? String ?: ""
                    // Treat "already sent" as success — PIN is already in their inbox
                    val alreadySent = message.contains("already been sent", ignoreCase = true) ||
                                      message.contains("already sent", ignoreCase = true)
                    if (success || alreadySent) {
                        isPinSent = true
                        binding.layoutPinSection.visibility = View.VISIBLE
                        binding.textEmailStatus.text = if (alreadySent)
                            "✉ A PIN was already sent to $email — check your inbox!"
                        else
                            "✉ PIN sent to $email — check your inbox!"
                        binding.textEmailStatus.visibility = View.VISIBLE
                        binding.buttonSignUp.text = "Resend PIN"
                        lockFormFields(true)

                        showToast(if (alreadySent) "PIN already sent — check your inbox" else "PIN sent to your email!")
                    } else {
                        showError(message.ifEmpty { "Failed to send PIN" })
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

    // ===== Step 2: verify PIN → register =====
    private fun verifyPinAndRegister() {
        val pin   = binding.editTextPin.text.toString().trim()
        val email = binding.editTextEmail.text.toString().trim()
        if (pin.length != 6) {
            binding.textPinError.visibility = View.VISIBLE
            binding.textPinError.text = "Please enter the 6-digit PIN"
            return
        }
        binding.textPinError.visibility = View.GONE
        showLoading(true)
        lifecycleScope.launch {
            try {
                val response = authRepository.verifyEmailPin(mapOf("email" to email, "pin" to pin))
                showLoading(false)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.get("success") == true && body["verified"] == true) {
                        // PIN verified — now register
                        val firstName   = binding.editTextFirstName.text.toString().trim()
                        val middleName  = binding.editTextMiddleName.text.toString().trim()
                        val lastName    = binding.editTextLastName.text.toString().trim()
                        val extensionN  = binding.editTextExtensionName.text.toString().trim()
                        val password    = binding.editTextPassword.text.toString().trim()
                        performRegistration(firstName, middleName, lastName, extensionN, email, password)
                    } else {
                        binding.textPinError.visibility = View.VISIBLE
                        binding.textPinError.text = body?.get("message") as? String ?: "Invalid PIN"
                    }
                } else {
                    binding.textPinError.visibility = View.VISIBLE
                    binding.textPinError.text = "Failed to verify PIN. Please try again."
                }
            } catch (e: Exception) {
                showLoading(false)
                binding.textPinError.visibility = View.VISIBLE
                binding.textPinError.text = "Error: ${e.message}"
            }
        }
    }

    // ===== Perform actual registration =====
    private fun performRegistration(
        firstName: String, middleName: String, lastName: String,
        extensionName: String, email: String, password: String
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
                    idNumber = null,
                    employeeNumber = null
                )
                result.fold(
                    onSuccess = { (success, message) ->
                        showLoading(false)
                        if (success) {
                            Snackbar.make(
                                binding.root,
                                "Account created! Your request is pending admin approval.",
                                Snackbar.LENGTH_LONG
                            ).setAction("LOGIN") { finish() }.show()
                            binding.root.postDelayed({ finish() }, 3000)
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

    // ===== Helpers =====
    private fun updatePasswordStrength(password: String) {
        if (password.isEmpty()) {
            binding.layoutPasswordStrength.visibility = View.GONE
            return
        }
        binding.layoutPasswordStrength.visibility = View.VISIBLE
        val (progress, color, label) = when {
            password.length < 8 -> Triple(25, 0xFFEF4444.toInt(), "Too short")
            Regex("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]+$").matches(password) -> Triple(100, 0xFF10B981.toInt(), "Strong")
            else -> Triple(55, 0xFFF59E0B.toInt(), "Fair")
        }
        binding.progressPasswordStrength.progress = progress
        binding.progressPasswordStrength.progressTintList = android.content.res.ColorStateList.valueOf(color)
        binding.textPasswordStrength.text = label
        binding.textPasswordStrength.setTextColor(color)
    }

    private fun lockFormFields(locked: Boolean) {
        binding.editTextFirstName.isEnabled = !locked
        binding.editTextMiddleName.isEnabled = !locked
        binding.editTextLastName.isEnabled = !locked
        binding.editTextExtensionName.isEnabled = !locked
        binding.editTextEmail.isEnabled = !locked
        binding.editTextPassword.isEnabled = !locked
        binding.editTextConfirmPassword.isEnabled = !locked
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.buttonSignUp.isEnabled = !isLoading
        binding.buttonConfirmPin.isEnabled = !isLoading
        binding.buttonSignUp.text = if (isLoading) "" else if (isPinSent) "Resend PIN" else "Create Account"
    }

    private fun showError(message: String) {
        binding.textError.apply {
            text = message
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(300).start()
        }
        ObjectAnimator.ofFloat(binding.cardForm, "translationX", 0f, 20f, -20f, 20f, -20f, 0f).apply {
            duration = 400
            start()
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun animateEntry() {
        val views = listOf(binding.viewAvatarCircle, binding.textTitle, binding.cardForm)
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 50f
            view.animate()
                .alpha(1f).translationY(0f)
                .setDuration(500).setStartDelay(index * 100L)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
    }

    private fun animateTextClick(view: View) {
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
    }
}
