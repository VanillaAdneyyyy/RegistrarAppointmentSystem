package com.example.registarappointmentsystem.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl
import com.example.registarappointmentsystem.databinding.ActivityStudentDashboardBinding
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.example.registarappointmentsystem.ui.profile.ProfileActivity
import com.example.registarappointmentsystem.ui.requests.RequestCredentialsActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch


class StudentDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentDashboardBinding
    private lateinit var appointmentRepository: AppointmentRepositoryImpl
    private lateinit var appointmentsAdapter: AppointmentsAdapter


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize ViewBinding
        binding = ActivityStudentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository
        appointmentRepository = AppointmentRepositoryImpl(RetrofitClient.apiService)

        initViews()
        setupNavigation()
        setupRecyclerView()
        setupAnimations()
        loadAppointments()
    }


    private fun initViews() {
        // In your XML, the title is binding.textToolbarTitle
        binding.textToolbarTitle.text = "My Dashboard"

        binding.imageProfile.setOnClickListener {
            navigateWithAnimation(ProfileActivity::class.java)
        }

        // Setup empty state button
        binding.buttonRequestCredentials?.setOnClickListener {
            navigateWithAnimation(RequestCredentialsActivity::class.java)
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    // Already on dashboard, do nothing
                    true
                }
                R.id.nav_request_credentials -> {
                    navigateWithAnimation(RequestCredentialsActivity::class.java)
                    true
                }
                R.id.nav_profile -> {
                    navigateWithAnimation(ProfileActivity::class.java)
                    true
                }
                R.id.nav_logout -> {
                    showLogoutConfirmation()
                    true
                }
                else -> false
            }
        }
    }


    private fun setupRecyclerView() {
        appointmentsAdapter = AppointmentsAdapter(
            onCancelClick = { appointment -> cancelAppointment(appointment) },
            onSelectDateTimeClick = { appointment -> selectDateTime(appointment) }
        )
        
        binding.recyclerAppointments.layoutManager = LinearLayoutManager(this)
        binding.recyclerAppointments.adapter = appointmentsAdapter
    }

    private fun loadAppointments() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            try {
                val appointments = appointmentRepository.getAllAppointments()
                
                binding.progressBar.visibility = View.GONE
                
                if (appointments.isEmpty()) {
                    showEmptyState()
                } else {
                    showAppointments(appointments)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                showEmptyState()
            }
        }
    }


    private fun showAppointments(appointments: List<Appointment>) {
        binding.recyclerAppointments.visibility = View.VISIBLE
        binding.emptyStateLayout?.visibility = View.GONE
        appointmentsAdapter.submitList(appointments)
    }

    private fun showEmptyState() {
        binding.recyclerAppointments.visibility = View.GONE
        binding.emptyStateLayout?.visibility = View.VISIBLE
    }

    private fun cancelAppointment(appointment: Appointment) {
        lifecycleScope.launch {
            val appointmentId = appointment.id
            if (appointmentId == null) {
                Snackbar.make(binding.root, "Invalid appointment ID", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            
            val result = appointmentRepository.cancelAppointment(appointmentId)
            
            result.fold(
                onSuccess = {
                    Snackbar.make(binding.root, "Appointment cancelled", Snackbar.LENGTH_SHORT).show()
                    loadAppointments() // Refresh list
                },
                onFailure = { error ->
                    Snackbar.make(binding.root, "Failed: ${error.message}", Snackbar.LENGTH_LONG).show()
                }
            )
        }
    }


    private fun selectDateTime(appointment: Appointment) {
        // TODO: Open date/time picker dialog
        Snackbar.make(binding.root, "Select date for ${appointment.purpose}", Snackbar.LENGTH_SHORT).show()
    }

    private fun setupAnimations() {
        // contentLayout is the main container in your XML
        binding.contentLayout.alpha = 0f
        binding.contentLayout.translationY = 50f

        binding.contentLayout.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator())
            .start()
    }


    private fun navigateWithAnimation(targetClass: Class<*>) {
        binding.contentLayout.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                startActivity(Intent(this, targetClass))
                binding.contentLayout.alpha = 1f
            }
            .start()
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

    private fun animateButtonPress(view: View) {
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

    override fun onResume() {
        super.onResume()
        binding.contentLayout.alpha = 1f
    }
}
