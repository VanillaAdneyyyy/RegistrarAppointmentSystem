package com.example.registarappointmentsystem.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.remote.RetrofitClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        authRepository = AuthRepositoryImpl(RetrofitClient.apiService)

        setupNavigation()
        loadUserProfile()
    }

    private fun setupNavigation() {
        binding.bottomNavigationProfile.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    startActivity(Intent(this, StudentDashboardActivity::class.java))
                    true
                }
                R.id.nav_request_credentials -> {
                    startActivity(Intent(this, RequestCredentialsActivity::class.java))
                    true
                }
                R.id.nav_profile -> {
                    // Already on profile, do nothing
                    true
                }
                R.id.nav_logout -> {
                    showLogoutConfirmation()
                    true
                }
                else -> false
            }
        }
        
        // Set profile as selected
        binding.bottomNavigationProfile.selectedItemId = R.id.nav_profile
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
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }


    private fun loadUserProfile() {
        // Get user email from SharedPreferences (saved during login)
        val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val userEmail = sharedPrefs.getString("user_email", null)

        if (userEmail == null) {
            Snackbar.make(binding.root, "Please login again", Snackbar.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            binding.progressBarProfile?.visibility = View.VISIBLE
            
            val result = authRepository.getUserByEmail(userEmail)
            
            binding.progressBarProfile?.visibility = View.GONE
            
            result.fold(
                onSuccess = { user ->
                    displayUserProfile(user)
                },
                onFailure = { error ->
                    Snackbar.make(binding.root, "Error loading profile: ${error.message}", Snackbar.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun displayUserProfile(user: com.example.registarappointmentsystem.data.model.User) {
        // Name fields
        binding.textFirstName?.text = user.first_name ?: "-"
        binding.textMiddleName?.text = user.middle_name ?: "-"
        binding.textLastName?.text = user.last_name ?: "-"
        binding.textExtensionName?.text = user.extension_name ?: "-"
        
        // Contact info
        binding.textProfileEmail?.text = user.email ?: "-"
        binding.textProfileRole?.text = user.role?.toString() ?: "-"
        binding.textProfileContact?.text = user.contact_number ?: "-"
        
        // Personal info
        binding.textBirthday?.text = user.birthday ?: "-"
        binding.textGender?.text = user.gender ?: "-"
        binding.textAddress?.text = user.address ?: "-"
        
        // Student/Employee info
        binding.textProfileStudentId?.text = user.student_number ?: "-"
        
        // Show ID number or Employee number based on role
        if (user.id_number != null) {
            binding.layoutIdNumber?.visibility = View.VISIBLE
            binding.textIdNumber?.text = user.id_number
        } else {
            binding.layoutIdNumber?.visibility = View.GONE
        }
        
        if (user.employee_number != null) {
            binding.layoutEmployeeNumber?.visibility = View.VISIBLE
            binding.textEmployeeNumber?.text = user.employee_number
        } else {
            binding.layoutEmployeeNumber?.visibility = View.GONE
        }
        
        // Status
        val statusText = when {
            user.is_approved == true && user.is_active == true -> "Active & Approved"
            user.is_approved == true -> "Approved"
            user.is_active == true -> "Active (Pending Approval)"
            else -> "Inactive"
        }
        binding.textProfileStatus?.text = statusText
    }

}
