package com.example.registarappointmentsystem.ui.dashboard

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.Appointment
import com.example.registarappointmentsystem.data.model.TimeSlot
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl
import com.example.registarappointmentsystem.databinding.ActivityStudentDashboardBinding
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.example.registarappointmentsystem.ui.profile.ProfileActivity
import com.example.registarappointmentsystem.ui.requests.RequestCredentialsActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar


class StudentDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudentDashboardBinding
    private lateinit var appointmentRepository: AppointmentRepositoryImpl
    private lateinit var appointmentsAdapter: AppointmentsAdapter
    private var autoRefreshJob: Job? = null

    // Payment screenshot fields
    private var currentPaymentScreenshotUri: Uri? = null
    private lateinit var screenshotLauncher: ActivityResultLauncher<String>
    // Callback set before launching the picker so the dialog can react
    private var onScreenshotPicked: ((Uri) -> Unit)? = null


    override fun onResume() {
        super.onResume()
        binding.contentLayout.alpha = 1f
        loadAppointments()
        // Poll the server silently every 30 seconds while the screen is visible
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                refreshSilently()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize ViewBinding
        binding = ActivityStudentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register image-picker launcher (must be done before onStart)
        screenshotLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                currentPaymentScreenshotUri = uri
                onScreenshotPicked?.invoke(uri)
            }
        }

        // Initialize repository
        appointmentRepository = AppointmentRepositoryImpl(RetrofitClient.apiService)

        initViews()
        setupNavigation()
        setupRecyclerView()
        setupAnimations()
        loadAppointments()
    }


    private fun initViews() {
        // Set toolbar title and greeting from SharedPreferences
        binding.textToolbarTitle.text = "My Dashboard"
        val sharedPrefsInit = getSharedPreferences("auth", MODE_PRIVATE)
        val firstName = sharedPrefsInit.getString("user_first_name", null)
        binding.textWelcomeUser.text = if (!firstName.isNullOrBlank()) "Welcome back, $firstName!" else "Welcome back,"

        binding.imageProfile.setOnClickListener {
            val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
            val userEmail = sharedPrefs.getString("user_email", null)
            val userId = sharedPrefs.getInt("user_id", -1)
            // SINGLE_TOP: reuse ProfileActivity if it's already at the top of the stack
            val intent = Intent(this, ProfileActivity::class.java).apply {
                putExtra("user_email", userEmail)
                putExtra("user_id", userId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        // Notification bell
        binding.buttonNotification.setOnClickListener {
            showNotifications()
        }
        binding.buttonRequestCredentials?.setOnClickListener {
            navigateWithAnimation(RequestCredentialsActivity::class.java)
        }
    }

    private fun setupNavigation() {
        // Set dashboard as the active tab BEFORE attaching the listener so the
        // initial assignment doesn't trigger navigation logic
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    // Already on dashboard — do nothing, keep it selected
                    true
                }
                R.id.nav_request_credentials -> {
                    navigateWithAnimation(RequestCredentialsActivity::class.java)
                    true
                }
                R.id.nav_profile -> {
                    val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
                    val userEmail = sharedPrefs.getString("user_email", null)
                    val userId = sharedPrefs.getInt("user_id", -1)
                    val intent = Intent(this, ProfileActivity::class.java).apply {
                        putExtra("user_email", userEmail)
                        putExtra("user_id", userId)
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    true
                }
                R.id.nav_logout -> {
                    showLogoutConfirmation()
                    // Return false so the logout tab does NOT stay highlighted
                    false
                }
                else -> false
            }
        }
        // Highlight the correct tab (after listener is set — nav_dashboard returns
        // true with no side-effects so no unintended navigation fires)
        binding.bottomNavigation.selectedItemId = R.id.nav_dashboard
    }


    private fun setupRecyclerView() {
        appointmentsAdapter = AppointmentsAdapter(
            onCancelClick = { appointment -> showCancelConfirmation(appointment) },
            onSelectDateTimeClick = { appointment -> selectDateTime(appointment) },
            onConfirmClick = { appointment -> confirmAppointment(appointment) },
            onPrintStubClick = { appointment -> printClaimStub(appointment) }
        )
        
        binding.recyclerAppointments.layoutManager = LinearLayoutManager(this)
        binding.recyclerAppointments.adapter = appointmentsAdapter
    }

    /** Background poll — no spinner so the UI doesn't flash every 30 s. */
    private fun refreshSilently() {
        lifecycleScope.launch {
            try {
                val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
                val userId = sharedPrefs.getInt("user_id", -1)
                if (userId <= 0) return@launch
                val appointments = appointmentRepository.getAppointmentsForUser(userId)
                if (appointments.isEmpty()) showEmptyState() else showAppointments(appointments)
            } catch (_: Exception) { /* swallow — network hiccup, try again next cycle */ }
        }
    }

    private fun loadAppointments() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            try {
                // Get current user ID from SharedPreferences
                val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
                val userId = sharedPrefs.getInt("user_id", -1)
                
                val appointments = if (userId > 0) {
                    appointmentRepository.getAppointmentsForUser(userId)
                } else {
                    emptyList()
                }

                
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
        updateStats(appointments)
    }

    private fun updateStats(appointments: List<Appointment>) {
        val total      = appointments.size
        val processing = appointments.count { it.status?.lowercase() in listOf("pending", "approved") }
        val ready      = appointments.count { it.status?.lowercase() in listOf("ready", "for_claiming") }
        val completed  = appointments.count { it.status?.lowercase() == "completed" }
        binding.tvStatTotal?.text     = total.toString()
        binding.tvStatProcessing?.text = processing.toString()
        binding.tvStatReady?.text     = ready.toString()
        binding.tvStatCompleted?.text = completed.toString()
    }

    private fun showEmptyState() {
        binding.recyclerAppointments.visibility = View.GONE
        binding.emptyStateLayout?.visibility = View.VISIBLE
        updateStats(emptyList())
    }

    private fun showCancelConfirmation(appointment: Appointment) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Appointment")
            .setMessage("Are you sure you want to cancel this request for \"${appointment.purpose}\"?")
            .setPositiveButton("Yes, Cancel") { _, _ -> cancelAppointment(appointment) }
            .setNegativeButton("Keep", null)
            .show()
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
                    loadAppointments()
                },
                onFailure = { error ->
                    Snackbar.make(binding.root, "Failed: ${error.message}", Snackbar.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun selectDateTime(appointment: Appointment) {
        val appointmentId = appointment.id ?: return

        // If payment is required and not yet verified, show payment dialog instead of date picker
        if (appointment.paymentAmount?.toDoubleOrNull() != null && appointment.paymentStatus != "verified") {
            showPaymentDialog(appointment)
            return
        }

        fun openPicker(errorMsg: String? = null) {
            val calendar = Calendar.getInstance()
            val picker = DatePickerDialog(
                this,
                { _, year, month, day ->
                    // Block weekends
                    val picked = Calendar.getInstance().apply { set(year, month, day) }
                    val dow = picked.get(Calendar.DAY_OF_WEEK)
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                        Snackbar.make(binding.root, "Weekends are not available. Please select a weekday.", Snackbar.LENGTH_LONG).show()
                        openPicker()
                        return@DatePickerDialog
                    }

                    val isoDate = String.format("%04d-%02d-%02d", year, month + 1, day)
                    val displayDate = String.format("%s %d, %d",
                        arrayOf("Jan","Feb","Mar","Apr","May","Jun",
                                 "Jul","Aug","Sep","Oct","Nov","Dec")[month],
                        day, year)

                    binding.progressBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val fetchedSlots = appointmentRepository.getTimeSlots(isoDate)
                        binding.progressBar.visibility = View.GONE

                        if (fetchedSlots.isEmpty()) {
                            Snackbar.make(binding.root, "Could not load time slots. Please check your connection and try again.", Snackbar.LENGTH_LONG).show()
                            return@launch
                        }

                    // Check if every slot is already taken
                    if (fetchedSlots.none { it.available }) {
                        AlertDialog.Builder(this@StudentDashboardActivity)
                            .setTitle("No Available Slots")
                            .setMessage("All time slots for $displayDate are fully booked. Please choose a different date.")
                            .setPositiveButton("Choose Another Date") { _, _ -> openPicker() }
                            .setNegativeButton("Cancel", null)
                            .show()
                        return@launch
                    }

                    showTimeSlotDialog(fetchedSlots, displayDate, isoDate, appointmentId)
                }
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }
            picker.show()
            if (errorMsg != null) {
                Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_LONG).show()
            }
        }

        openPicker()
    }

    private fun showPaymentDialog(appointment: Appointment) {
        val amount  = appointment.paymentAmount?.toDoubleOrNull() ?: 0.0
        val amtStr  = "\u20b1${String.format("%.2f", amount)}"
        val payStat = appointment.paymentStatus

        // If already submitted, show a simple status dialog
        if (payStat == "submitted") {
            AlertDialog.Builder(this)
                .setTitle("🕐 Payment Submitted")
                .setMessage("Amount: $amtStr\nReference: ${appointment.paymentReference}\n\nYour reference has been submitted. The registrar will verify it shortly.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Inflate the custom payment dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_payment, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        // — Header —
        dialogView.findViewById<TextView>(R.id.tvPaymentPurpose).text =
            appointment.purpose ?: "Document request"

        // — Amount Due —
        dialogView.findViewById<TextView>(R.id.tvAmountDue).text = amtStr

        // — Rejected warning —
        if (payStat == "rejected") {
            dialogView.findViewById<View>(R.id.layoutRejectedWarning).visibility = View.VISIBLE
        }

        // — Close button —
        dialogView.findViewById<View>(R.id.btnPaymentClose).setOnClickListener { dialog.dismiss() }

        // — Screenshot preview —
        val imgPreview = dialogView.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.imgScreenshotPreview)
        val tvUploadLabel = dialogView.findViewById<TextView>(R.id.tvUploadLabel)

        // Reset screenshot state when dialog opens
        currentPaymentScreenshotUri = null

        // — Pick screenshot button —
        dialogView.findViewById<View>(R.id.btnPickScreenshot).setOnClickListener {
            onScreenshotPicked = { uri ->
                imgPreview.setImageURI(uri)
                imgPreview.visibility = View.VISIBLE
                tvUploadLabel.text = "Screenshot selected ✓"
            }
            screenshotLauncher.launch("image/*")
        }

        // — Submit button —
        val btnSubmit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSubmitPayment)
        val refInput  = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRefNumber)

        btnSubmit.setOnClickListener {
            val ref = refInput.text.toString().trim()
            if (ref.isEmpty()) {
                Snackbar.make(binding.root, "Please enter your reference number.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.text = "Submitting…"

            lifecycleScope.launch {
                binding.progressBar.visibility = View.VISIBLE

                // Upload screenshot first if one was chosen
                val screenshotUri = currentPaymentScreenshotUri
                if (screenshotUri != null) {
                    val upResult = appointmentRepository.uploadPaymentProof(appointment.id!!, screenshotUri, this@StudentDashboardActivity)
                    if (upResult.isFailure) {
                        binding.progressBar.visibility = View.GONE
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "✓  Submit Payment Proof"
                        Snackbar.make(binding.root, "Screenshot upload failed: ${upResult.exceptionOrNull()?.message}", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                }

                // Submit reference number
                val result = appointmentRepository.submitPayment(appointment.id!!, ref)
                binding.progressBar.visibility = View.GONE

                result.fold(
                    onSuccess = {
                        dialog.dismiss()
                        Snackbar.make(binding.root, "✅ Payment submitted! Waiting for registrar to verify.", Snackbar.LENGTH_LONG).show()
                        loadAppointments()
                    },
                    onFailure = { e ->
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "✓  Submit Payment Proof"
                        Snackbar.make(binding.root, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                )
            }
        }

        dialog.show()
    }

    private fun showTimeSlotDialog(
        slots: List<TimeSlot>,
        displayDate: String,
        isoDate: String,
        appointmentId: Int
    ) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_slot_picker, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.tvPickerDate).text = displayDate
        view.findViewById<View>(R.id.btnPickerClose).setOnClickListener { dialog.dismiss() }

        val morningSlots   = slots.filter { "AM" in it.time || it.time == "12:00 PM" }
        val afternoonSlots = slots.filter { "PM" in it.time && it.time != "12:00 PM" }

        // Containers are vertical LinearLayouts; we fill them with horizontal rows of 4 chips each.
        val containerMorning   = view.findViewById<LinearLayout>(R.id.gridMorning)
        val containerAfternoon = view.findViewById<LinearLayout>(R.id.gridAfternoon)

        fun populateContainer(container: LinearLayout, section: List<TimeSlot>) {
            container.removeAllViews()
            val dp = resources.displayMetrics.density
            val chipH = (42 * dp).toInt()
            val margin = (4 * dp).toInt()

            // Split into rows of 4
            section.chunked(4).forEach { rowSlots ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 0, 0, margin) }
                }
                // Fill up to 4 slots; add invisible placeholder if fewer than 4
                repeat(4) { idx ->
                    val slot = rowSlots.getOrNull(idx)
                    val chip = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, chipH, 1f).also {
                            it.setMargins(margin, 0, margin, 0)
                        }
                        gravity = Gravity.CENTER
                        textSize = 11.5f
                        if (slot == null) {
                            // Invisible filler to keep alignment
                            visibility = View.INVISIBLE
                        } else if (slot.available) {
                            text = slot.time
                            paintFlags = paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                            setTextColor(Color.parseColor("#166534"))
                            background = ContextCompat.getDrawable(this@StudentDashboardActivity, R.drawable.bg_slot_available)
                            setOnClickListener {
                                AlertDialog.Builder(this@StudentDashboardActivity)
                                    .setTitle("Confirm Pickup Time")
                                    .setMessage("Book pickup for:\n\n\uD83D\uDCC5 $displayDate\n\uD83D\uDD50 ${slot.time}\n\nAre you sure?")
                                    .setPositiveButton("Yes, Book It") { _, _ ->
                                        dialog.dismiss()
                                        lifecycleScope.launch {
                                            binding.progressBar.visibility = View.VISIBLE
                                            val result = appointmentRepository.bookTimeSlot(
                                                isoDate, slot.time, appointmentId,
                                                getSharedPreferences("auth", MODE_PRIVATE)
                                                    .getString("user_full_name", "") ?: ""
                                            )
                                            binding.progressBar.visibility = View.GONE
                                            result.fold(
                                                onSuccess = {
                                                    Snackbar.make(binding.root, "\u2705 Pickup set: $displayDate at ${slot.time}", Snackbar.LENGTH_LONG).show()
                                                    loadAppointments()
                                                },
                                                onFailure = { error ->
                                                    Snackbar.make(binding.root, "Failed to book: ${error.message}", Snackbar.LENGTH_LONG).show()
                                                }
                                            )
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        } else {
                            text = slot.time
                            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            setTextColor(Color.parseColor("#B91C1C"))
                            background = ContextCompat.getDrawable(this@StudentDashboardActivity, R.drawable.bg_slot_booked)
                            setOnClickListener {
                                Snackbar.make(binding.root, "\u274C This slot is fully booked. Please choose another time.", Snackbar.LENGTH_SHORT).show()
                            }
                        }
                    }
                    row.addView(chip)
                }
                container.addView(row)
            }
            container.visibility = if (section.isEmpty()) View.GONE else View.VISIBLE
        }

        populateContainer(containerMorning,   morningSlots)
        populateContainer(containerAfternoon, afternoonSlots)

        if (morningSlots.isEmpty())
            view.findViewById<View>(R.id.tvMorningLabel).visibility = View.GONE
        if (afternoonSlots.isEmpty())
            view.findViewById<View>(R.id.tvAfternoonLabel).visibility = View.GONE

        dialog.show()
        // Set peekHeight after show() so the behavior is fully attached
        dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.75).toInt()
    }

    /**
     * Local fallback slots (matches web app).
     * Morning 8:30 AM - 12:00 PM  |  Afternoon 1:30 PM - 4:30 PM  (10-min intervals)
     */
    private fun buildTimeSlots(): List<String> {
        val slots = mutableListOf<String>()
        for (hour in 8..11) {
            for (min in 0 until 60 step 10) {
                if (hour == 8 && min < 30) continue
                slots.add("$hour:${min.toString().padStart(2,'0')} AM")
            }
        }
        slots.add("12:00 PM")
        for (hour in 13..16) {
            for (min in 0 until 60 step 10) {
                if (hour == 13 && min < 30) continue
                if (hour == 16 && min > 30) continue
                slots.add("${hour - 12}:${min.toString().padStart(2,'0')} PM")
            }
        }
        // Mirror server: skip every 4th slot as a buffer (3 bookable + 1 buffer gap)
        return slots.filterIndexed { index, _ -> (index + 1) % 4 != 0 }
    }
    private fun confirmAppointment(appointment: Appointment) {
        val appointmentId = appointment.id ?: return
        AlertDialog.Builder(this)
            .setTitle("Confirm Pickup")
            .setMessage("Confirm that you have received your document for \"${appointment.purpose}\"?")
            .setPositiveButton("Confirm") { _, _ ->
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    val result = appointmentRepository.updateAppointment(
                        appointmentId,
                        mapOf("status" to "completed")
                    )
                    binding.progressBar.visibility = View.GONE
                    result.fold(
                        onSuccess = {
                            Snackbar.make(binding.root, "Pickup confirmed!", Snackbar.LENGTH_SHORT).show()
                            loadAppointments()
                        },
                        onFailure = { error ->
                            Snackbar.make(binding.root, "Failed: ${error.message}", Snackbar.LENGTH_LONG).show()
                        }
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                // SINGLE_TOP prevents duplicate instances when tapping the same nav item twice
                val intent = Intent(this, targetClass).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                binding.contentLayout.alpha = 1f
            }
            .start()
    }

    private fun printClaimStub(appointment: Appointment) {
        val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val fullName = sharedPrefs.getString("user_full_name", appointment.username ?: "—") ?: "—"

        // Format dates nicely
        fun fmtDate(raw: String?): String {
            if (raw.isNullOrEmpty()) return "—"
            return try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val d = sdf.parse(raw.take(10)) ?: return raw
                java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).format(d)
            } catch (e: Exception) { raw }
        }

        val pickupDate   = fmtDate(appointment.student_pickup_date)
        val pickupTime   = appointment.pickup_time ?: "—"
        val availableDate = fmtDate(appointment.ready_date)
        val purpose      = appointment.purpose ?: "—"
        val refId        = appointment.id?.toString() ?: "—"
        val status       = appointment.status?.uppercase() ?: "—"

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <style>
                body { font-family: Arial, sans-serif; margin: 40px; }
                .stub { border: 2px dashed #1e3a5f; border-radius: 10px;
                        padding: 30px; max-width: 480px; margin: auto; }
                h2 { text-align: center; color: #1e3a5f; margin-bottom: 4px; }
                .subtitle { text-align: center; color: #666; font-size: 13px; margin-bottom: 20px; }
                hr { border: none; border-top: 1px solid #ccc; margin: 16px 0; }
                table { width: 100%; border-collapse: collapse; font-size: 14px; }
                td { padding: 8px 4px; vertical-align: top; }
                td:first-child { color: #555; width: 40%; font-weight: bold; }
                .status-badge { display: inline-block; padding: 4px 12px;
                                background: #1e3a5f; color: #fff;
                                border-radius: 99px; font-size: 12px; font-weight: bold; }
                .footer { text-align: center; color: #999; font-size: 11px; margin-top: 20px; }
              </style>
            </head>
            <body>
              <div class="stub">
                <h2>REGISTRAR CLAIM STUB</h2>
                <div class="subtitle">University of Pangasinan — Registrar's Office</div>
                <hr/>
                <table>
                  <tr><td>Ref ID:</td><td>#$refId</td></tr>
                  <tr><td>Student:</td><td>$fullName</td></tr>
                  <tr><td>Document:</td><td>$purpose</td></tr>
                  <tr><td>Available From:</td><td>$availableDate</td></tr>
                  <tr><td>Pickup Date:</td><td>$pickupDate</td></tr>
                  <tr><td>Pickup Time:</td><td>$pickupTime</td></tr>
                  <tr><td>Status:</td><td><span class="status-badge">$status</span></td></tr>
                </table>
                <hr/>
                <div class="footer">Please bring this stub and a valid ID when claiming your document.</div>
              </div>
            </body>
            </html>
        """.trimIndent()

        val webView = WebView(this)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "ClaimStub_Ref${refId}"
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, printAdapter, null)
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
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
                    AlertDialog.Builder(this@StudentDashboardActivity)
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

                AlertDialog.Builder(this@StudentDashboardActivity)
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

}
