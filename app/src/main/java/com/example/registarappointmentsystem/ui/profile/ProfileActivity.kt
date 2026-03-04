package com.example.registarappointmentsystem.ui.profile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl
import com.example.registarappointmentsystem.data.repository.AuthRepositoryImpl
import com.example.registarappointmentsystem.databinding.ActivityProfileBinding
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.ui.requests.RequestCredentialsActivity
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch



class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var authRepository: AuthRepositoryImpl
    private lateinit var appointmentRepository: AppointmentRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            binding.bottomNavigationProfile.setPadding(0, 0, 0, sysBarBottom)
            v.setPadding(0, 0, 0, if (imeBottom > 0) imeBottom else 0)
            insets
        }

        // Initialize repository
        authRepository = AuthRepositoryImpl(RetrofitClient.apiService)
        appointmentRepository = AppointmentRepositoryImpl(RetrofitClient.apiService)

        setupNavigation()
        loadUserProfile()
    }

    private fun setupNavigation() {
        binding.bottomNavigationProfile.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    // FLAG_ACTIVITY_CLEAR_TOP pops Profile and reuses the existing Dashboard
                    // instead of creating a new instance — avoids back stack buildup
                    val intent = Intent(this, StudentDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    true
                }
                R.id.nav_request_credentials -> {
                    // CLEAR_TOP ensures only one RequestCredentials exists in the stack
                    val intent = Intent(this, RequestCredentialsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    true
                }
                R.id.nav_profile -> {
                    // Already on profile, do nothing
                    true
                }
                R.id.nav_logout -> {
                    showLogoutConfirmation()
                    // Return false so logout tab does NOT stay highlighted when cancelled
                    false
                }
                else -> false
            }
        }

        // Highlight the profile tab after listener is attached (nav_profile returns
        // true with no side-effects so no unintended navigation fires)
        binding.bottomNavigationProfile.selectedItemId = R.id.nav_profile

        // Notification bell
        binding.buttonNotification.setOnClickListener {
            showNotifications()
        }
    }

    private fun showNotifications() {
        val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val userId = sharedPrefs.getInt("user_id", -1)

        if (userId <= 0) {
            AlertDialog.Builder(this)
                .setTitle("Notifications")
                .setMessage("Please log in to view your appointment notifications.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        lifecycleScope.launch {
            try {
                val appointments = appointmentRepository.getAppointmentsForUser(userId)

                if (appointments.isEmpty()) {
                    AlertDialog.Builder(this@ProfileActivity)
                        .setTitle("Notifications")
                        .setMessage("No appointment requests found.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }

                val lines = appointments.map { appt ->
                    val doc = appt.purpose ?: "Document"
                    val statusLabel = when (appt.status?.lowercase()) {
                        "approved"  -> "Approved — Ready for pickup scheduling"
                        "ready"     -> "Ready for pickup"
                        "pending"   -> "Pending review"
                        "rejected"  -> "Rejected"
                        "completed" -> "Completed"
                        "no_show"   -> "No show"
                        "cancelled" -> "Cancelled"
                        else        -> appt.status ?: "Unknown"
                    }
                    val icon = when (appt.status?.lowercase()) {
                        "approved"  -> "✅"
                        "ready"     -> "📦"
                        "pending"   -> "🕐"
                        "rejected"  -> "❌"
                        "completed" -> "✓"
                        "no_show"   -> "⚠️"
                        else        -> "•"
                    }
                    "$icon  $doc\n     Status: $statusLabel"
                }

                AlertDialog.Builder(this@ProfileActivity)
                    .setTitle("Your Appointment Updates")
                    .setMessage(lines.joinToString("\n\n"))
                    .setPositiveButton("Close", null)
                    .show()

            } catch (e: Exception) {
                Snackbar.make(binding.root, "Could not load notifications: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Yes") { _, _ -> performLogout() }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun performLogout() {
        getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }


    private fun loadUserProfile() {
        Log.d("ProfileActivity", "loadUserProfile() called")
        
        // First try to get email from Intent extras (passed from Dashboard)
        var userEmail = intent.getStringExtra("user_email")
        Log.d("ProfileActivity", "Email from Intent extras: '$userEmail'")
        
        // If not in Intent, fall back to SharedPreferences
        if (userEmail.isNullOrEmpty()) {
            val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
            userEmail = sharedPrefs.getString("user_email", null)
            Log.d("ProfileActivity", "Email from SharedPreferences: '$userEmail'")
        }

        if (userEmail.isNullOrEmpty()) {
            Log.e("ProfileActivity", "Email is null or empty!")
            Snackbar.make(binding.root, "Please login again", Snackbar.LENGTH_LONG).show()
            return
        }
        
        Log.d("ProfileActivity", "Email found: '$userEmail', proceeding to fetch user data")



        lifecycleScope.launch {
            binding.progressBarProfile?.visibility = View.VISIBLE
            
            try {
                Log.d("ProfileActivity", "Fetching user data from API...")
                val result = authRepository.getUserByEmail(userEmail)
                
                result.fold(
                    onSuccess = { user ->
                        Log.d("ProfileActivity", "User loaded successfully: ${user.email}")
                        displayUserProfile(user)
                    },
                    onFailure = { error ->
                        Log.e("ProfileActivity", "Failed to load user: ${error.message}", error)
                        Snackbar.make(binding.root, "Error loading profile: ${error.message}", Snackbar.LENGTH_LONG).show()
                    }
                )
            } catch (e: Exception) {
                Log.e("ProfileActivity", "Exception during profile load", e)
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.progressBarProfile?.visibility = View.GONE
            }
        }
    }



    private fun displayUserProfile(user: com.example.registarappointmentsystem.data.model.User) {
        // Build full name for header
        val fullName = listOfNotNull(user.first_name, user.middle_name, user.last_name, user.extension_name)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        binding.textProfileFullName?.text = fullName.ifEmpty { "—" }

        // Avatar initials (first letter of first + last name)
        val initials = buildString {
            user.first_name?.firstOrNull()?.let { append(it.uppercaseChar()) }
            user.last_name?.firstOrNull()?.let { append(it.uppercaseChar()) }
        }.ifEmpty { "?" }
        binding.imageAvatar?.text = initials

        // Individual name fields
        binding.textFirstName?.text = user.first_name?.ifEmpty { "—" } ?: "—"
        binding.textMiddleName?.text = user.middle_name?.ifEmpty { "—" } ?: "—"
        binding.textLastName?.text = user.last_name?.ifEmpty { "—" } ?: "—"
        binding.textExtensionName?.text = user.extension_name?.ifEmpty { "—" } ?: "—"

        // Contact info
        binding.textProfileEmail?.text = (user.personal_email ?: user.email)?.ifEmpty { "—" } ?: "—"
        binding.textProfileContact?.text = user.contact_number?.ifEmpty { "—" } ?: "—"

        // Role badge
        binding.textProfileRole?.text = user.role?.replaceFirstChar { it.uppercaseChar() } ?: "User"

        // Personal info
        binding.textBirthday?.text = user.birthday?.ifEmpty { "—" } ?: "—"
        binding.textGender?.text = user.gender?.ifEmpty { "—" } ?: "—"
        binding.textAddress?.text = user.address?.ifEmpty { "—" } ?: "—"

        // Student ID
        if (!user.id_number.isNullOrEmpty()) {
            binding.layoutIdNumber?.visibility = View.VISIBLE
            binding.textIdNumber?.text = user.id_number
        } else {
            binding.layoutIdNumber?.visibility = View.GONE
        }

        // Employee Number
        if (!user.employee_number.isNullOrEmpty()) {
            binding.layoutEmployeeNumber?.visibility = View.VISIBLE
            binding.textEmployeeNumber?.text = user.employee_number
        } else {
            binding.layoutEmployeeNumber?.visibility = View.GONE
        }

        // Status
        val statusText = when {
            user.is_approved == true && user.is_active == true -> "● Active & Approved"
            user.is_approved == true -> "● Approved"
            user.is_active == true -> "● Active (Pending Approval)"
            else -> "○ Inactive"
        }
        binding.textProfileStatus?.text = statusText
    }


}
