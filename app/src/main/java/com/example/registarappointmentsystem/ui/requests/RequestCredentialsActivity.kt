package com.example.registarappointmentsystem.ui.requests

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.example.registarappointmentsystem.databinding.ActivityRequestCredentialsBinding
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.ui.profile.ProfileActivity
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.example.registarappointmentsystem.viewmodel.RequestCredentialsViewModel
import com.example.registarappointmentsystem.viewmodel.RequestCredentialsViewModelFactory
import com.google.android.material.navigation.NavigationView

class RequestCredentialsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestCredentialsBinding

    private val viewModel: RequestCredentialsViewModel by viewModels {
        RequestCredentialsViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRequestCredentialsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        observeViewModel()
    }

    private fun setupUi() {
        val drawerLayout: DrawerLayout = findViewById(com.example.registarappointmentsystem.R.id.drawerLayoutRequest)
        val navigationView: NavigationView = findViewById(com.example.registarappointmentsystem.R.id.navigationViewRequest)

        binding.buttonMenu.setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

        binding.buttonBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.buttonSubmit.setOnClickListener {
            val type = binding.editCredentialType.text?.toString().orEmpty()
            val reason = binding.editReason.text?.toString().orEmpty()
            viewModel.onSubmitClicked(type, reason)
        }

        navigationView.setNavigationItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                com.example.registarappointmentsystem.R.id.nav_dashboard -> {
                    startActivity(Intent(this, StudentDashboardActivity::class.java))
                }
                com.example.registarappointmentsystem.R.id.nav_request_credentials -> {
                    // Already on this screen; do nothing.
                }
                com.example.registarappointmentsystem.R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                }
                com.example.registarappointmentsystem.R.id.nav_logout -> {
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                }
            }
            drawerLayout.closeDrawer(Gravity.START)
            true
        }
    }

    private fun observeViewModel() {
        viewModel.formState.observe(this) { state ->
            binding.textCredentialTypeError.apply {
                text = state.credentialTypeError.orEmpty()
                visibility = if (state.credentialTypeError != null) View.VISIBLE else View.GONE
            }
            binding.textReasonError.apply {
                text = state.reasonError.orEmpty()
                visibility = if (state.reasonError != null) View.VISIBLE else View.GONE
            }
        }

        viewModel.submitResult.observe(this) { result ->
            binding.progressBar.visibility = if (result.isLoading) View.VISIBLE else View.GONE

            binding.textSubmitStatus.apply {
                visibility = if (result.data != null || result.errorMessage != null) View.VISIBLE else View.GONE
                text = when {
                    result.data != null -> "Request submitted successfully."
                    result.errorMessage != null -> result.errorMessage
                    else -> ""
                }
            }
        }
    }
}

