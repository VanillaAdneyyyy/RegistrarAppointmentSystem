package com.example.registarappointmentsystem.ui.requests

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.DocumentType
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl
import com.example.registarappointmentsystem.databinding.ActivityRequestCredentialsBinding
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.ui.profile.ProfileActivity
import com.example.registarappointmentsystem.viewmodel.RequestCredentialsViewModel
import com.example.registarappointmentsystem.viewmodel.RequestCredentialsViewModelFactory
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class RequestCredentialsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestCredentialsBinding

    private val selectedDocIds = mutableSetOf<Int>()
    private var docTypesList: List<DocumentType> = emptyList()
    private var selectedTotal = 0.0

    private val viewModel: RequestCredentialsViewModel by viewModels {
        RequestCredentialsViewModelFactory()
    }

    private val appointmentRepository by lazy {
        AppointmentRepositoryImpl(RetrofitClient.apiService)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRequestCredentialsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            // Bottom nav absorbs system gesture bar (draws its background behind it)
            binding.bottomNavigationRequest.setPadding(0, 0, 0, sysBarBottom)
            // Only push the whole layout up when the keyboard is actually open
            v.setPadding(0, 0, 0, if (imeBottom > 0) imeBottom else 0)
            insets
        }
        setupUi()
        setupNavigation()
        observeViewModel()
        fetchDocumentTypes()
    }

    private fun fetchDocumentTypes() {
        lifecycleScope.launch {
            try {
                val types = appointmentRepository.getDocumentTypes()
                docTypesList = types
                buildCheckboxes(types)
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Could not load document types", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun buildCheckboxes(types: List<DocumentType>) {
        val container = binding.checkboxContainer
        container.removeAllViews()
        selectedDocIds.clear()
        selectedTotal = 0.0
        updatePriceDisplay()

        types.forEach { docType ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8.dpToPx() }
            }

            val checkBox = CheckBox(this).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (selectedDocIds.size >= 3) {
                            this.isChecked = false
                            Snackbar.make(binding.root, "Maximum 3 documents per request", Snackbar.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }
                        selectedDocIds.add(docType.id)
                        selectedTotal += docType.priceDouble()
                    } else {
                        selectedDocIds.remove(docType.id)
                        selectedTotal -= docType.priceDouble()
                    }
                    updatePriceDisplay()
                    binding.textCredentialTypeError.visibility = View.GONE
                }
            }

            val nameText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = docType.name
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@RequestCredentialsActivity, R.color.text_primary))
                setPadding(4.dpToPx(), 0, 0, 0)
            }

            val priceText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "₱${docType.price}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@RequestCredentialsActivity, R.color.primary_dark_green))
            }

            row.addView(checkBox)
            row.addView(nameText)
            row.addView(priceText)
            container.addView(row)
        }
    }

    private fun updatePriceDisplay() {
        val hasSelection = selectedDocIds.isNotEmpty()
        binding.priceBreakdownLayout.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.textPriceTotal.text = "₱%.2f".format(selectedTotal)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun setupDropdown() {
        // Dropdown replaced by dynamic checkboxes — intentionally empty
    }

    private fun setupUi() {
        binding.buttonBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Show the Former Student ID field only for guest users
        val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val isGuest = sharedPrefs.getString("user_role", "guest") == "guest"
        binding.layoutGuestSection.visibility = if (isGuest) View.VISIBLE else View.GONE

        binding.buttonSubmit.setOnClickListener {
            animateButtonPress(binding.buttonSubmit)

            val purpose = if (selectedDocIds.isEmpty()) {
                // fallback: if no checkboxes loaded, use custom purpose field
                binding.editCustomPurpose.text?.toString().orEmpty().trim()
            } else {
                // Build purpose string from selected doc names
                docTypesList.filter { it.id in selectedDocIds }
                    .joinToString(", ") { it.name }
            }
            val reason = binding.editReason.text?.toString().orEmpty().trim()
            val studentIdNumber = binding.editStudentIdNumber.text?.toString().orEmpty().trim()

            // Validate former student ID for guest users
            if (isGuest && studentIdNumber.isBlank()) {
                binding.textStudentIdError.text = "Please enter your former Student ID number"
                binding.textStudentIdError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            binding.textStudentIdError.visibility = View.GONE

            val username = sharedPrefs.getString("user_full_name", "")
                ?.ifBlank { sharedPrefs.getString("user_email", "") ?: "" }
                ?: ""
            val userId = sharedPrefs.getInt("user_id", -1)

            viewModel.onSubmitClicked(
                username = username,
                purpose = purpose,
                reason = reason,
                contactNumber = "",
                userId = userId,
                documentTypeIds = selectedDocIds.toList(),
                studentIdNumber = if (isGuest) studentIdNumber else null
            )
        }
    }

    private fun setupNavigation() {
        // Highlight the Request tab as currently active
        binding.bottomNavigationRequest.selectedItemId = R.id.nav_request_credentials

        binding.bottomNavigationRequest.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    val intent = Intent(this, StudentDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    true
                }
                R.id.nav_request_credentials -> {
                    // Already here, do nothing
                    true
                }
                R.id.nav_profile -> {
                    val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
                    val intent = Intent(this, ProfileActivity::class.java).apply {
                        putExtra("user_email", sharedPrefs.getString("user_email", null))
                        putExtra("user_id", sharedPrefs.getInt("user_id", -1))
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
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

        // Notification bell: load appointments and show status panel
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
                    AlertDialog.Builder(this@RequestCredentialsActivity)
                        .setTitle("Notifications")
                        .setMessage("No appointment requests found.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }

                // Build notification message from appointments
                val lines = appointments.map { appt ->
                    val doc = appt.purpose ?: "Document"
                    val statusLabel = when (appt.status?.lowercase()) {
                        "approved"  -> "Approved - Ready for pickup"
                        "pending"   -> "Pending review"
                        "rejected"  -> "Rejected"
                        "completed" -> "Completed"
                        "cancelled" -> "Cancelled"
                        else        -> appt.status ?: "Unknown"
                    }
                    val icon = when (appt.status?.lowercase()) {
                        "approved"  -> "✅"
                        "pending"   -> "🕐"
                        "rejected"  -> "❌"
                        "completed" -> "✓"
                        "cancelled" -> "–"
                        else        -> "•"
                    }
                    "$icon  $doc\n     Status: $statusLabel"
                }

                AlertDialog.Builder(this@RequestCredentialsActivity)
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
            .setPositiveButton("Yes") { _, _ ->
                getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply()
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
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
            binding.buttonSubmit.isEnabled = !result.isLoading

            if (result.data != null) {
                binding.textSubmitStatus.apply {
                    text = "Request submitted successfully!"
                    setTextColor(resources.getColor(R.color.status_approved, null))
                    visibility = View.VISIBLE
                }
                Snackbar.make(binding.root, "Credential request submitted!", Snackbar.LENGTH_LONG).show()
                binding.root.postDelayed({
                    binding.editReason.text?.clear()
                    binding.editCustomPurpose.text?.clear()
                    binding.editStudentIdNumber.text?.clear()
                    binding.layoutCustomPurpose.visibility = View.GONE
                    binding.textSubmitStatus.visibility = View.GONE
                    selectedDocIds.clear()
                    selectedTotal = 0.0
                    buildCheckboxes(docTypesList)
                    finish()
                }, 2000)
            }

            result.errorMessage?.let { error ->
                binding.textSubmitStatus.apply {
                    text = error
                    setTextColor(resources.getColor(R.color.error_red, null))
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
    }
}