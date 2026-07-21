package com.example.registarappointmentsystem.ui.profile

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputFilter
import android.text.method.DigitsKeyListener
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.User
import com.example.registarappointmentsystem.data.remote.RetrofitClient
import com.example.registarappointmentsystem.data.remote.request.RequestGuestDeletionPinRequest
import com.example.registarappointmentsystem.data.remote.request.VerifyGuestDeletionPinRequest
import com.example.registarappointmentsystem.data.repository.AppointmentRepositoryImpl
import com.example.registarappointmentsystem.data.repository.AuthRepositoryImpl
import android.app.Dialog
import java.text.SimpleDateFormat
import java.util.Locale
import com.example.registarappointmentsystem.databinding.ActivityProfileBinding
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.ui.requests.RequestCredentialsActivity
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.registarappointmentsystem.utils.SessionManager
import com.example.registarappointmentsystem.utils.NotificationHelper
import com.example.registarappointmentsystem.utils.BackgroundSyncScheduler



class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var authRepository: AuthRepositoryImpl
    private lateinit var appointmentRepository: AppointmentRepositoryImpl
    private lateinit var sessionManager: SessionManager
    private lateinit var notificationHelper: NotificationHelper
    // Guard: prevents the nav listener from reacting when we programmatically reset the selection
    private var restoringNavSelection = false
    private var suppressRootImePadding = false
    private var autoRefreshJob: Job? = null
    private var cachedAppointments: List<com.example.registarappointmentsystem.data.model.Appointment> = emptyList()
    private var forcePasswordChangeRequired = false
    private var lastVerifiedGuestDeletionPin: String? = null
    private var registrarEmail: String = "registrar@upang.edu.ph"
    private var registrarPhone: String = "(075) 123-4567"
    private var registrarMobile: String = "0917-000-0000"
    
    // Permission request launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result will be handled automatically by NotificationManagerCompat
    }

    private fun isAuthenticated(): Boolean {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        return prefs.getInt("user_id", -1) > 0
    }

    private fun enforceAuthOrRedirect(): Boolean {
        if (isAuthenticated()) return true
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
        return false
    }

    private fun isForcePasswordChangeRequired(): Boolean {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        return prefs.getBoolean("must_change_password", false)
    }

    override fun onResume() {
        super.onResume()

        if (!enforceAuthOrRedirect()) return
        if (!::binding.isInitialized) return
        
        restoringNavSelection = true
        binding.bottomNavigationProfile.selectedItemId = R.id.nav_profile
        restoringNavSelection = false
        lifecycleScope.launch { syncNotificationPrefsFromServer() }
        loadRegistrarContactSettings()
        refreshSilently()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(3_000)
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

        if (!enforceAuthOrRedirect()) return
        
        sessionManager = SessionManager(this)
        notificationHelper = NotificationHelper(this)
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forcePasswordChangeRequired = intent.getBooleanExtra("forcePasswordChange", false) || isForcePasswordChangeRequired()
        lifecycleScope.launch { syncNotificationPrefsFromServer() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            binding.bottomNavigationProfile.setPadding(0, 0, 0, sysBarBottom)
            val bottomPadding = if (!suppressRootImePadding && imeBottom > 0) imeBottom else 0
            v.setPadding(0, 0, 0, bottomPadding)
            insets
        }

        // Initialize repository
        authRepository = AuthRepositoryImpl(RetrofitClient.apiService)
        appointmentRepository = AppointmentRepositoryImpl(RetrofitClient.apiService)

        setupActivityTracking()
        setupNavigation()
        loadRegistrarContactSettings()
        loadUserProfile()
    }

    private fun setupActivityTracking() {
        binding.root.setOnTouchListener { _, _ ->
            // Keep tracking user interactions for responsiveness
            false
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigationProfile.setOnItemSelectedListener { item ->
            if (restoringNavSelection) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    if (forcePasswordChangeRequired) {
                        Snackbar.make(binding.root, "Please change your password first.", Snackbar.LENGTH_SHORT).show()
                        return@setOnItemSelectedListener false
                    }
                    // FLAG_ACTIVITY_CLEAR_TOP pops Profile and reuses the existing Dashboard
                    // instead of creating a new instance — avoids back stack buildup
                    val intent = Intent(this, StudentDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_request_credentials -> {
                    if (forcePasswordChangeRequired) {
                        Snackbar.make(binding.root, "Please change your password first.", Snackbar.LENGTH_SHORT).show()
                        return@setOnItemSelectedListener false
                    }
                    // CLEAR_TOP ensures only one RequestCredentials exists in the stack
                    val intent = Intent(this, RequestCredentialsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    overridePendingTransition(0, 0)
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

        // Highlight the profile tab
        binding.bottomNavigationProfile.selectedItemId = R.id.nav_profile

        // Style notification badge
        binding.textNotifBadge.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.parseColor("#EF4444"))
        }
        // Notification bell
        binding.buttonNotification.setOnClickListener {
            showNotifications()
        }
        // Help button
        binding.buttonHelp.setOnClickListener { showContactDialog() }
    }

    private fun showNotifications() {
        val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val userId = sharedPrefs.getInt("user_id", -1)

        if (userId <= 0) {
            Snackbar.make(binding.root, "Please log in to view notifications.", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (cachedAppointments.isNotEmpty()) {
            showNotificationsBottomSheet(cachedAppointments)
            return
        }

        lifecycleScope.launch {
            try {
                val appointments = appointmentRepository.getAppointmentsForUser(userId)
                val filtered = filterClearedNotifications(appointments)
                cachedAppointments = filtered
                updateNotifBadge(filtered)
                showNotificationsBottomSheet(filtered)
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Could not load notifications: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showNotificationsBottomSheet(appointments: List<com.example.registarappointmentsystem.data.model.Appointment>) {
        val sheet = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(56, 40, 56, 24)
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = "🔔  Notifications"
            textSize = 17f
            setTextColor(Color.parseColor("#0f172a"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)
        val prefsBtn = TextView(this).apply {
            text = "Preferences"
            textSize = 13f
            setTextColor(Color.parseColor("#2563eb"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 24, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showNotificationPreferencesDialog()
            }
        }
        header.addView(prefsBtn)
        // Clear All button
        val clearBtn = TextView(this).apply {
            text = "Clear All"
            textSize = 13f
            setTextColor(Color.parseColor("#ef4444"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val uid = getSharedPreferences("auth", MODE_PRIVATE).getInt("user_id", -1)
                if (uid > 0) {
                    lifecycleScope.launch {
                        try { RetrofitClient.apiService.clearNotifications(uid) } catch (_: Exception) {}
                    }
                }
                // Store cleared timestamp so notifications stay hidden across refreshes
                getSharedPreferences("auth", MODE_PRIVATE).edit()
                    .putLong("notifications_cleared_at", System.currentTimeMillis())
                    .apply()
                cachedAppointments = emptyList()
                updateNotifBadge(emptyList())
                sheet.dismiss()
            }
        }
        header.addView(clearBtn)
        container.addView(header)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#e2e8f0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        container.addView(divider)

        if (appointments.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No appointment updates yet."
                textSize = 14f
                setTextColor(Color.parseColor("#94a3b8"))
                gravity = Gravity.CENTER
                setPadding(0, 80, 0, 80)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            container.addView(empty)
        } else {
            val sorted = appointments.sortedByDescending { it.updated_at ?: it.created_at }

            for (appt in sorted) {
                val status = appt.status?.lowercase() ?: continue
                val icon = when (status) {
                    "pending"      -> "🕐"
                    "approved"     -> "✅"
                    "processing"   -> "🔄"
                    "incomplete"   -> "⚠️"
                    "ready"        -> "🏫"
                    "for_claiming" -> "📦"
                    "completed"    -> "🎉"
                    "cancelled"    -> "❌"
                    "rejected"     -> "❌"
                    "no_show"      -> "🚫"
                    else           -> "•"
                }
                val statusLabel = when (status) {
                    "pending"      -> "Pending review"
                    "approved"     -> "Approved — documents being prepared"
                    "processing"   -> "Processing your request"
                    "incomplete"   -> "Incomplete requirements"
                    "ready"        -> "Ready — schedule your pickup"
                    "for_claiming" -> "Documents ready for claiming!"
                    "completed"    -> "Completed"
                    "cancelled"    -> "Cancelled"
                    "rejected"     -> "Rejected"
                    "no_show"      -> "Missed pickup — auto-transitioning to for claiming"
                    else           -> appt.status ?: "Unknown"
                }
                val dotColor = when (status) {
                    "pending", "processing", "incomplete" -> "#f59e0b"
                    "approved", "completed"               -> "#10b981"
                    "ready", "for_claiming"               -> "#8b5cf6"
                    "cancelled", "rejected", "no_show"    -> "#ef4444"
                    else                                   -> "#94a3b8"
                }
                val bgColor = when (status) {
                    "pending", "processing", "incomplete" -> "#fffbeb"
                    "approved", "completed"               -> "#f0fdf4"
                    "ready", "for_claiming"               -> "#faf5ff"
                    "cancelled", "rejected", "no_show"    -> "#fff1f2"
                    else                                   -> "#f8fafc"
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(56, 28, 56, 28)
                    setBackgroundColor(Color.parseColor(bgColor))
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { sheet.dismiss() }
                }

                // Dot indicator
                val dot = View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(dotColor))
                    }
                    layoutParams = LinearLayout.LayoutParams(20, 20).also { it.topMargin = 8; it.marginEnd = 24 }
                }
                row.addView(dot)

                // Text block
                val textBlock = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val msgView = TextView(this).apply {
                    text = "$icon  ${appt.purpose ?: "Document"}"
                    textSize = 14f
                    setTextColor(Color.parseColor("#0f172a"))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val statusView = TextView(this).apply {
                    text = statusLabel
                    textSize = 12f
                    setTextColor(Color.parseColor(dotColor))
                }
                textBlock.addView(msgView)
                textBlock.addView(statusView)

                appt.admin_comment?.takeIf { it.isNotBlank() }?.let { note ->
                    val noteView = TextView(this).apply {
                        text = "Note: $note"
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#64748b"))
                        setPadding(0, 4, 0, 0)
                    }
                    textBlock.addView(noteView)
                }

                row.addView(textBlock)

                // Arrow hint
                val arrow = TextView(this).apply {
                    text = "→"
                    textSize = 16f
                    setTextColor(Color.parseColor("#94a3b8"))
                    setPadding(16, 0, 0, 0)
                }
                row.addView(arrow)
                container.addView(row)

                // Row divider
                container.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#f1f5f9"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }

        val card = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.WHITE)
            }
            clipToOutline = true
            addView(ScrollView(this@ProfileActivity).apply { addView(container) })
        }
        sheet.setContentView(card)
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.93f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also {
                it.windowAnimations = android.R.style.Animation_Dialog
                it.dimAmount = 0.5f
            }
        }
        sheet.setCanceledOnTouchOutside(true)
        sheet.show()
    }

    private fun refreshSilently() {
        lifecycleScope.launch {
            try {
                val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
                val userId = sharedPrefs.getInt("user_id", -1)
                if (userId <= 0) return@launch
                val appts = appointmentRepository.getAppointmentsForUser(userId)
                val filtered = filterClearedNotifications(appts)
                cachedAppointments = filtered
                checkStatusChangesAndNotify(filtered)
                updateNotifBadge(filtered)
            } catch (_: Exception) { /* silent — network hiccup */ }
        }
    }

    /**
     * Detects status and payment changes for appointments and sends appropriate notifications
     * using NotificationHelper methods. Stores previous state in SharedPreferences to detect deltas.
     */
    private fun checkStatusChangesAndNotify(appointments: List<com.example.registarappointmentsystem.data.model.Appointment>) {
        val cache = getSharedPreferences("appointment_status_cache", MODE_PRIVATE)
        val cacheEditor = cache.edit()
        val (statusUpdatesEnabled, readyPickupEnabled) = getNotificationPrefs()
        
        appointments.forEach { appt ->
            val apptId = appt.id ?: return@forEach
            val cacheKeyStatus = "status_$apptId"
            val cacheKeyPaymentStatus = "payment_status_$apptId"
            val cacheKeyPaymentAmount = "payment_amount_$apptId"
            
            val prevStatus = cache.getString(cacheKeyStatus, null)
            val prevPaymentStatus = cache.getString(cacheKeyPaymentStatus, null)
            val prevPaymentAmount = cache.getString(cacheKeyPaymentAmount, null)
            val prevPayAmt = prevPaymentAmount?.toDoubleOrNull() ?: 0.0
            val currPayAmt = appt.paymentAmount?.toDoubleOrNull() ?: 0.0
            
            // Initialize cache on first load: store current state
            if (prevStatus == null) {
                cacheEditor.putString(cacheKeyStatus, appt.status)
                cacheEditor.putString(cacheKeyPaymentStatus, appt.paymentStatus)
                cacheEditor.putString(cacheKeyPaymentAmount, appt.paymentAmount)
            }

            // Registrar changed required fee to FREE.
            if (statusUpdatesEnabled && prevStatus != null && prevPayAmt > 0.0 && currPayAmt <= 0.0) {
                notificationHelper.notifyPaymentWaived(appt.purpose ?: "Document")
            }
            
            // Status change detected
            if (prevStatus != null && prevStatus != appt.status) {
                when (appt.status?.lowercase()) {
                    "approved" -> {
                        if (statusUpdatesEnabled) {
                            val isPaymentPending = appt.paymentStatus != null && appt.paymentStatus?.lowercase() == "pending"
                            notificationHelper.notifyApproved(appt.purpose ?: "Document", appt.ready_date ?: "", isPaymentPending)
                        }
                    }
                    "ready", "for_claiming" -> {
                        if (statusUpdatesEnabled && readyPickupEnabled) {
                            notificationHelper.notifyReadyForPickup(appt.purpose ?: "Document")
                        }
                    }
                    "rejected" -> {
                        if (statusUpdatesEnabled) {
                            notificationHelper.notifyRejected(appt.purpose ?: "Document", appt.reason ?: "Not specified")
                        }
                    }
                }
            }
            
            // Payment status change detected
            if (prevPaymentStatus != null && prevPaymentStatus != appt.paymentStatus) {
                when (appt.paymentStatus?.lowercase()) {
                    "pending" -> {
                        if (statusUpdatesEnabled) {
                            if ((appt.paymentAmount?.toDoubleOrNull() ?: 0.0) > 0.0) {
                                notificationHelper.notifyPaymentRequired(appt.purpose ?: "Document", appt.paymentAmount ?: "0.00")
                            } else {
                                notificationHelper.notifyPaymentWaived(appt.purpose ?: "Document")
                            }
                        }
                    }
                    "verified" -> {
                        if (statusUpdatesEnabled) {
                            notificationHelper.notifyPaymentVerified(appt.purpose ?: "Document")
                        }
                    }
                }
            }
            
            // Update cache with current state
            cacheEditor.putString(cacheKeyStatus, appt.status)
            cacheEditor.putString(cacheKeyPaymentStatus, appt.paymentStatus)
            cacheEditor.putString(cacheKeyPaymentAmount, appt.paymentAmount)
        }
        
        cacheEditor.apply()
    }

    private fun getNotificationPrefs(): Pair<Boolean, Boolean> {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val statusUpdates = prefs.getBoolean("pref_status_updates", true)
        val readyPickup = prefs.getBoolean("pref_ready_pickup", true)
        return statusUpdates to readyPickup
    }

    private suspend fun syncNotificationPrefsFromServer(): Pair<Boolean, Boolean> {
        val localPrefs = getNotificationPrefs()
        val authPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val userId = authPrefs.getInt("user_id", -1)
        if (userId <= 0) return localPrefs

        return try {
            val response = RetrofitClient.apiService.getNotificationPreferences(userId)
            if (!response.isSuccessful) return localPrefs
            val body = response.body() ?: return localPrefs
            val prefsMap = body["preferences"] as? Map<*, *>
            val statusUpdates = (prefsMap?.get("statusUpdates") as? Boolean) ?: localPrefs.first
            val readyPickup = (prefsMap?.get("readyPickup") as? Boolean) ?: localPrefs.second
            authPrefs.edit()
                .putBoolean("pref_status_updates", statusUpdates)
                .putBoolean("pref_ready_pickup", readyPickup)
                .apply()
            statusUpdates to readyPickup
        } catch (_: Exception) {
            localPrefs
        }
    }

    private fun showNotificationPreferencesDialog() {
        lifecycleScope.launch {
            val (statusInitial, readyInitial) = syncNotificationPrefsFromServer()
            val prefs = getSharedPreferences("auth", MODE_PRIVATE)

            val dialog = Dialog(this@ProfileActivity)
            val container = LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(22), dp(22), dp(16))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.WHITE)
                }
            }

            container.addView(TextView(this@ProfileActivity).apply {
                text = "Notification Preferences"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            })
            container.addView(TextView(this@ProfileActivity).apply {
                text = "Control email and in-app appointment updates"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(4), 0, dp(14))
            })

            val statusCard = LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(dp(1), Color.parseColor("#E2E8F0"))
                }
            }
            val statusSwitch = SwitchCompat(this@ProfileActivity).apply {
                isChecked = statusInitial
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BFDBFE"))
            }
            statusCard.addView(LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ProfileActivity).apply {
                    text = "Status updates"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(this@ProfileActivity).apply {
                    text = "Approved, cancelled, processing, payment updates"
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                })
            })
            statusCard.addView(statusSwitch)
            statusCard.setOnClickListener { statusSwitch.isChecked = !statusSwitch.isChecked }
            container.addView(statusCard)

            container.addView(View(this@ProfileActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            })

            val readyCard = LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(dp(1), Color.parseColor("#E2E8F0"))
                }
            }
            val readySwitch = SwitchCompat(this@ProfileActivity).apply {
                isChecked = readyInitial
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BFDBFE"))
            }
            readyCard.addView(LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ProfileActivity).apply {
                    text = "Ready for pickup"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(this@ProfileActivity).apply {
                    text = "Notify when documents are ready/for claiming"
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                })
            })
            readyCard.addView(readySwitch)
            readyCard.setOnClickListener { readySwitch.isChecked = !readySwitch.isChecked }
            container.addView(readyCard)

            val buttonRow = LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
            }
            val closeBtn = TextView(this@ProfileActivity).apply {
                text = "Close"
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#475569"))
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).also { it.marginEnd = dp(8) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#E2E8F0"))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            }
            val saveBtn = TextView(this@ProfileActivity).apply {
                text = "Save"
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#2563EB"))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val statusValue = statusSwitch.isChecked
                    val readyValue = readySwitch.isChecked
                    prefs.edit()
                        .putBoolean("pref_status_updates", statusValue)
                        .putBoolean("pref_ready_pickup", readyValue)
                        .apply()

                    lifecycleScope.launch {
                        val userId = getSharedPreferences("auth", MODE_PRIVATE).getInt("user_id", -1)
                        if (userId > 0) {
                            try {
                                RetrofitClient.apiService.updateNotificationPreferences(
                                    userId,
                                    mapOf(
                                        "statusUpdates" to statusValue,
                                        "readyPickup" to readyValue
                                    )
                                )
                            } catch (e: Exception) {
                                Snackbar.make(binding.root, "Saved locally, but server sync failed.", Snackbar.LENGTH_LONG).show()
                                Log.e("ProfileActivity", "Notification preference sync failed", e)
                            }
                        }
                    }

                    dialog.dismiss()
                    Snackbar.make(binding.root, "Notification preferences updated.", Snackbar.LENGTH_SHORT).show()
                }
            }
            buttonRow.addView(closeBtn)
            buttonRow.addView(saveBtn)
            container.addView(buttonRow)

            dialog.setContentView(container)
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            }
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
        }
    }

    private fun updateNotifBadge(appointments: List<com.example.registarappointmentsystem.data.model.Appointment>) {
        val active = appointments.count { a ->
            a.status?.lowercase() !in listOf("completed", "cancelled", "no_show", "rejected")
        }
        val badge = binding.textNotifBadge
        if (active > 0) {
            badge.text = if (active > 99) "99+" else active.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun filterClearedNotifications(appointments: List<com.example.registarappointmentsystem.data.model.Appointment>): List<com.example.registarappointmentsystem.data.model.Appointment> {
        val clearedAt = getSharedPreferences("auth", MODE_PRIVATE).getLong("notifications_cleared_at", 0L)
        if (clearedAt == 0L) return appointments
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return appointments.filter { appt ->
            val ts = appt.updated_at ?: appt.created_at ?: return@filter true
            try {
                val parsed = sdf.parse(ts.replace(Regex("\\.\\d+Z?$"), ""))
                (parsed?.time ?: 0L) > clearedAt
            } catch (_: Exception) { true }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showLogoutConfirmation() {
        val dialog = Dialog(this)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(android.graphics.Color.WHITE)
            }
            clipToOutline = true
            setPadding(dp(32), dp(36), dp(32), dp(28))
        }

        // Red circle icon
        val iconCircle = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(16) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#FEE2E2"))
            }
        }
        val iconView = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_power_off)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#DC2626"))
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
        }
        iconCircle.addView(iconView)
        card.addView(iconCircle)

        // Title
        val title = TextView(this).apply {
            text = "Sign Out"
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#0f172a"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) }
        }
        card.addView(title)

        // Message
        val message = TextView(this).apply {
            text = "Are you sure you want to sign out?"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#64748b"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(24) }
        }
        card.addView(message)

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#64748b"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(android.graphics.Color.parseColor("#F1F5F9"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).also { it.marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        val signOutBtn = TextView(this).apply {
            text = "Sign Out"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(android.graphics.Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).also { it.marginStart = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss(); performLogout() }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(signOutBtn)
        card.addView(buttonRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.5f }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun showContactDialog() {
        val email = registrarEmail
        val phone = registrarPhone
        val mobile = registrarMobile

        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
            clipToOutline = true
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }

        val iconCircle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(14)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#DBEAFE"))
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_email)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
            layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
        }
        iconCircle.addView(icon)
        container.addView(iconCircle)

        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(18))
        }
        val titleTV = TextView(this).apply {
            text = "Contact the Registrar"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
        }
        val subtitleTV = TextView(this).apply {
            text = "Registrar's office contact details"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(6), 0, 0)
            gravity = Gravity.CENTER
        }
        headerCard.addView(titleTV)
        headerCard.addView(subtitleTV)
        container.addView(headerCard)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250))
        }
        val contactsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
        }

        val emailBtn = createContactButton(
            email, "Email", "MAIL", Color.parseColor("#DBEAFE"),
            Color.parseColor("#F8FAFC"), Color.parseColor("#BFDBFE")
        ) {
            startActivity(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:$email") })
        }
        contactsLayout.addView(emailBtn)

        val landlineBtn = createContactButton(
            phone, "Landline", "TEL", Color.parseColor("#E0E7FF"),
            Color.parseColor("#F8FAFC"), Color.parseColor("#C7D2FE")
        ) {
            startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:${phone.replace(Regex("[^\\d+]"), "")}") })
        }
        contactsLayout.addView(landlineBtn)

        val mobileBtn = createContactButton(
            mobile, "Mobile", "SMS", Color.parseColor("#DCFCE7"),
            Color.parseColor("#F8FAFC"), Color.parseColor("#86EFAC")
        ) {
            startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:${mobile.replace(Regex("[^\\d+]"), "")}") })
        }
        contactsLayout.addView(mobileBtn)

        val officeHours = TextView(this).apply {
            text = "Office Hours: Mon-Fri, 8:00 AM - 5:00 PM"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#334155"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(2, Color.parseColor("#CBD5E1"))
            }
            gravity = Gravity.CENTER
        }
        contactsLayout.addView(officeHours)

        scrollView.addView(contactsLayout)
        container.addView(scrollView)

        val closeBtn = Button(this).apply {
            setText("Done")
            setTextSize(15f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).also { it.topMargin = dp(18) }
            stateListAnimator = null
            setOnClickListener { dialog.dismiss() }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#2563EB"))
                setStroke(2, Color.parseColor("#1D4ED8"))
            }
        }
        container.addView(closeBtn)

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.5f }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun loadRegistrarContactSettings() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getSettings()
                if (!response.isSuccessful) return@launch
                val body = response.body() ?: return@launch
                val settings = body["settings"] as? Map<*, *> ?: return@launch

                registrarEmail = settings["registrar_email"]?.toString()?.takeIf { it.isNotBlank() } ?: registrarEmail
                registrarPhone = settings["registrar_phone"]?.toString()?.takeIf { it.isNotBlank() } ?: registrarPhone
                registrarMobile = settings["registrar_mobile"]?.toString()?.takeIf { it.isNotBlank() } ?: registrarMobile
            } catch (e: Exception) {
                Log.w("ProfileActivity", "Failed to load registrar contact settings", e)
            }
        }
    }
    
    private fun createContactButton(
        text: String,
        label: String,
        badge: String,
        iconBg: Int,
        btnBg: Int,
        btnBorder: Int,
        action: () -> Unit
    ): LinearLayout {
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            setBackgroundColor(btnBg)
            setPadding(14, 14, 14, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 12 }
            isClickable = true
            isFocusable = true
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#00000020")),
                null, null
            )
            setOnClickListener { action() }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(btnBg)
                setStroke(2, btnBorder)
            }
        }
        
        // Icon
        val iconContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(40, 40).also { it.marginEnd = 14 }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(iconBg)
            }
        }
        val iconTV = TextView(this).apply {
            setText(badge)
            setTextSize(10f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1E3A8A"))
            setGravity(Gravity.CENTER)
        }
        iconContainer.addView(iconTV, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER }
        )
        btn.addView(iconContainer)
        
        // Text
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val labelTV = TextView(this).apply {
            setTextSize(10f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#94a3b8"))
            setText(label.uppercase())
        }
        val valueTV = TextView(this).apply {
            setTextSize(13f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1e293b"))
            setPadding(0, 4, 0, 0)
            setText(text)
        }
        val hintTV = TextView(this).apply {
            setTextSize(10f)
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 3, 0, 0)
            setText("Tap to open")
        }
        textContainer.addView(labelTV)
        textContainer.addView(valueTV)
        textContainer.addView(hintTV)
        btn.addView(textContainer)

        val chevron = TextView(this).apply {
            setText("›")
            textSize = 20f
            setTextColor(Color.parseColor("#94A3B8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 0, 0, 0)
        }
        btn.addView(chevron)
        
        return btn
    }

    private fun performLogout() {
        BackgroundSyncScheduler.stop(this)
        getSharedPreferences("auth", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("appointment_status_cache", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("payment_notifs", MODE_PRIVATE).edit().clear().apply()
        try {
            NotificationManagerCompat.from(this).cancelAll()
        } catch (_: SecurityException) {
            // Ignore when POST_NOTIFICATIONS is not granted on Android 13+
        }
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }


    private fun loadUserProfile() {
        Log.d("ProfileActivity", "loadUserProfile() called")
        val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val userId = sharedPrefs.getInt("user_id", -1)
        if (userId <= 0) {
            Log.e("ProfileActivity", "Invalid user_id in SharedPreferences")
            Snackbar.make(binding.root, "Please login again", Snackbar.LENGTH_LONG).show()
            return
        }

        Log.d("ProfileActivity", "user_id found: $userId, proceeding to fetch profile data")

        lifecycleScope.launch {
            binding.progressBarProfile?.visibility = View.VISIBLE
            
            try {
                Log.d("ProfileActivity", "Fetching profile by id from API...")
                val response = RetrofitClient.apiService.getProfileById(userId)

                if (response.isSuccessful) {
                    val payload = response.body()
                    val data = (payload?.get("user") as? Map<*, *>)
                        ?: (payload?.get("data") as? Map<*, *>)
                    if (data != null) {
                        val user = User(
                            id = (data["id"] as? Number)?.toInt() ?: userId,
                            first_name = data["first_name"]?.toString(),
                            middle_name = data["middle_name"]?.toString(),
                            last_name = data["last_name"]?.toString(),
                            extension_name = data["extension_name"]?.toString(),
                            email = data["email"]?.toString().orEmpty(),
                            personal_email = data["personal_email"]?.toString(),
                            role = data["role"]?.toString() ?: "student",
                            is_active = data["is_active"] as? Boolean,
                            is_approved = data["is_approved"] as? Boolean,
                            id_number = data["id_number"]?.toString(),
                            employee_number = data["employee_number"]?.toString(),
                            contact_number = data["contact_number"]?.toString(),
                            gender = data["gender"]?.toString(),
                            birthday = data["birthday"]?.toString(),
                            address = data["address"]?.toString(),
                            must_change_password = when (val forceValue = data["must_change_password"]) {
                                is Boolean -> forceValue
                                is Number -> forceValue.toInt() != 0
                                is String -> forceValue == "1" || forceValue.equals("true", ignoreCase = true)
                                else -> false
                            },
                            created_at = data["created_at"]?.toString(),
                            updated_at = data["updated_at"]?.toString()
                        )

                        Log.d("ProfileActivity", "User loaded successfully: ${user.email}")
                        displayUserProfile(user)
                    } else {
                        Log.e("ProfileActivity", "Profile response missing data payload")
                        Snackbar.make(binding.root, "Error loading profile: invalid response", Snackbar.LENGTH_LONG).show()
                    }
                } else {
                    Log.e("ProfileActivity", "Failed to load profile: ${response.code()} ${response.message()}")
                    Snackbar.make(binding.root, "Error loading profile: ${response.message()}", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileActivity", "Exception during profile load", e)
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.progressBarProfile?.visibility = View.GONE
            }
        }
    }



    private fun displayUserProfile(user: User) {
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
        binding.textBirthday?.text = formatBirthday(user.birthday)
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

        // Show/hide incomplete banner
        val isIncomplete = user.contact_number.isNullOrBlank() || user.gender.isNullOrBlank() ||
            user.birthday.isNullOrBlank() || user.address.isNullOrBlank()
        binding.bannerProfileIncomplete?.visibility = if (isIncomplete) View.VISIBLE else View.GONE
        binding.bannerEditBtn?.setOnClickListener { showProfileEditDialog(user) }
        binding.btnEditProfile?.setOnClickListener { showProfileEditDialog(user) }
        binding.btnChangePassword?.setOnClickListener { showChangePasswordDialog(user.email) }
        val isGuest = user.role.equals("guest", ignoreCase = true)
        binding.btnDeleteAccount?.visibility = if (isGuest) View.VISIBLE else View.GONE
        binding.btnDeleteAccount?.setOnClickListener { showGuestDeleteWarning(user) }

        forcePasswordChangeRequired = forcePasswordChangeRequired || (user.must_change_password == true)
        getSharedPreferences("auth", MODE_PRIVATE).edit()
            .putBoolean("must_change_password", forcePasswordChangeRequired)
            .apply()

        // Auto-open edit dialog if arriving from login with incomplete profile
        if (isIncomplete && intent.getBooleanExtra("isProfileIncomplete", false)) {
            intent.removeExtra("isProfileIncomplete") // prevent re-showing on rotation
            binding.root.post { showProfileEditDialog(user) }
        }

        if (forcePasswordChangeRequired) {
            binding.root.post {
                showChangePasswordDialog(
                    user.email,
                    mandatory = true,
                    onChanged = {
                        forcePasswordChangeRequired = false
                        getSharedPreferences("auth", MODE_PRIVATE).edit()
                            .putBoolean("must_change_password", false)
                            .apply()
                        startActivity(Intent(this, StudentDashboardActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    private fun showChangePasswordDialog(
        accountEmail: String?,
        mandatory: Boolean = false,
        onChanged: (() -> Unit)? = null
    ) {
        if (accountEmail.isNullOrBlank()) {
            Snackbar.make(binding.root, "Missing account email. Please log in again.", Snackbar.LENGTH_LONG).show()
            return
        }

        val dialog = Dialog(this)
        val previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        suppressRootImePadding = true
        ViewCompat.requestApplyInsets(binding.root)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
            }
        }

        root.addView(TextView(this).apply {
            text = "Change Password"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        })
        root.addView(TextView(this).apply {
            text = if (mandatory) {
                "You must change your temporary password before continuing."
            } else {
                "Use at least 8 characters for your new password."
            }
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(4), 0, dp(14))
        })

        fun makePasswordField(hint: String): Pair<LinearLayout, EditText> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(dp(1), Color.parseColor("#E2E8F0"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(10) }
            }

            val input = EditText(this).apply {
                this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
                setTextColor(Color.parseColor("#0F172A"))
                setHintTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(8), dp(8), dp(8))
                background = null
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                , 1f)
            }

            val eyeBtn = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_view)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#64748B"))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginStart = dp(8) }
                isClickable = true
                isFocusable = true
                contentDescription = "Show password"
            }

            var visible = false
            eyeBtn.setOnClickListener {
                visible = !visible
                if (visible) {
                    input.transformationMethod = HideReturnsTransformationMethod.getInstance()
                    eyeBtn.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                    eyeBtn.contentDescription = "Hide password"
                } else {
                    input.transformationMethod = PasswordTransformationMethod.getInstance()
                    eyeBtn.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#64748B"))
                    eyeBtn.contentDescription = "Show password"
                }
                input.setSelection(input.text?.length ?: 0)
            }

            row.addView(input)
            row.addView(eyeBtn)
            return row to input
        }

        val (currentRow, currentField) = makePasswordField("Current password")
        val (newRow, newField) = makePasswordField("New password")
        val (confirmRow, confirmField) = makePasswordField("Confirm new password")
        root.addView(currentRow)
        root.addView(newRow)
        root.addView(confirmRow)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).also { it.marginEnd = dp(8) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        val saveBtn = TextView(this)
        saveBtn.apply {
            text = "Update"
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val currentPassword = currentField.text.toString().trim()
                val newPassword = newField.text.toString().trim()
                val confirmPassword = confirmField.text.toString().trim()

                if (currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
                    Snackbar.make(binding.root, "Please fill in all password fields.", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (newPassword.length < 8) {
                    Snackbar.make(binding.root, "New password must be at least 8 characters.", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (currentPassword == newPassword) {
                    Snackbar.make(binding.root, "New password must be different from current password.", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!newPassword.matches(Regex("^(?=.*[A-Za-z])(?=.*\\d)\\S{8,}$"))) {
                    Snackbar.make(binding.root, "Password must include at least one letter and one number. Special characters are allowed.", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (newPassword != confirmPassword) {
                    Snackbar.make(binding.root, "New password and confirm password do not match.", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                saveBtn.isClickable = false
                saveBtn.alpha = 0.7f
                saveBtn.text = "Updating..."

                lifecycleScope.launch {
                    try {
                        val response = RetrofitClient.apiService.changePassword(
                            mapOf(
                                "email" to accountEmail,
                                "currentPassword" to currentPassword,
                                "newPassword" to newPassword
                            )
                        )

                        if (response.isSuccessful && response.body()?.get("success") == true) {
                            dialog.dismiss()
                            Snackbar.make(binding.root, "Password changed successfully.", Snackbar.LENGTH_LONG).show()
                            onChanged?.invoke()
                        } else {
                            val bodyMsg = response.body()?.get("error")?.toString()
                                ?: response.body()?.get("message")?.toString()
                            val errorMsg = if (!bodyMsg.isNullOrBlank()) {
                                bodyMsg
                            } else {
                                try {
                                    val raw = response.errorBody()?.string().orEmpty()
                                    val json = org.json.JSONObject(raw)
                                    json.optString("error").ifBlank {
                                        json.optString("message").ifBlank { "Failed to change password." }
                                    }
                                } catch (_: Exception) {
                                    "Failed to change password."
                                }
                            }
                            Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_LONG).show()
                            saveBtn.isClickable = true
                            saveBtn.alpha = 1f
                            saveBtn.text = "Update"
                        }
                    } catch (e: Exception) {
                        Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                        saveBtn.isClickable = true
                        saveBtn.alpha = 1f
                        saveBtn.text = "Update"
                    }
                }
            }
        }

        if (!mandatory) {
            buttonRow.addView(cancelBtn)
        }
        buttonRow.addView(saveBtn)
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener {
            window.setSoftInputMode(previousSoftInputMode)
            suppressRootImePadding = false
            ViewCompat.requestApplyInsets(binding.root)
        }
        dialog.setCanceledOnTouchOutside(!mandatory)
        dialog.setCancelable(!mandatory)
        dialog.show()
    }

    private fun showGuestDeleteWarning(user: User) {
        val dialog = Dialog(this)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(28), dp(30), dp(28), dp(24))
        }

        val iconCircle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(14)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#FEE2E2"))
            }
        }
        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_alert)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
        }
        iconCircle.addView(iconView)
        card.addView(iconCircle)

        card.addView(TextView(this).apply {
            text = "Delete Your Account"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        })

        card.addView(TextView(this).apply {
            text = "This action is permanent. Your guest account access will be removed."
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(22) }
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).also { it.marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        val continueBtn = TextView(this).apply {
            text = "Continue"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).also { it.marginStart = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                showGuestDeleteTypeConfirm(user)
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(continueBtn)
        card.addView(buttonRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.5f }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun showGuestDeleteTypeConfirm(user: User) {
        val dialog = Dialog(this)
        val previousSoftInputMode = window.attributes.softInputMode
        suppressRootImePadding = true
        ViewCompat.requestApplyInsets(binding.root)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }

        card.addView(TextView(this).apply {
            text = "Safety Check"
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER_HORIZONTAL
        })

        card.addView(TextView(this).apply {
            text = "Type DELETE to continue."
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(14))
        })

        val input = EditText(this).apply {
            hint = "Type DELETE"
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }
        card.addView(input)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }

        val backBtn = TextView(this).apply {
            text = "Back"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        val sendPinBtn = TextView(this).apply {
            text = "Send PIN"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginStart = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (input.text.toString().trim().uppercase() != "DELETE") {
                    input.error = "Type DELETE"
                    Snackbar.make(binding.root, "Please type DELETE to continue.", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                requestGuestDeletePin(user)
            }
        }

        buttonRow.addView(backBtn)
        buttonRow.addView(sendPinBtn)
        card.addView(buttonRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.5f }
        }
        dialog.setOnDismissListener {
            window.setSoftInputMode(previousSoftInputMode)
            suppressRootImePadding = false
            ViewCompat.requestApplyInsets(binding.root)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun requestGuestDeletePin(user: User) {
        val userId = user.id ?: run {
            Snackbar.make(binding.root, "Invalid session. Please log in again.", Snackbar.LENGTH_LONG).show()
            return
        }
        val email = user.email.ifBlank {
            Snackbar.make(binding.root, "Missing account email.", Snackbar.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.requestGuestDeletionPin(
                    RequestGuestDeletionPinRequest(
                        userId = userId,
                        email = email
                    )
                )
                if (!response.isSuccessful || response.body()?.get("success") != true) {
                    val message = response.body()?.get("message")?.toString() ?: "Failed to request deletion PIN"
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                Snackbar.make(binding.root, "Deletion PIN sent to your email.", Snackbar.LENGTH_LONG).show()
                showGuestDeletePinDialog(user)
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error requesting PIN: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showGuestDeletePinDialog(user: User) {
        val dialog = Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }

        card.addView(TextView(this).apply {
            text = "Verify Deletion PIN"
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER_HORIZONTAL
        })

        card.addView(TextView(this).apply {
            text = "Enter the PIN sent to your email."
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(14))
        })

        val input = EditText(this).apply {
            hint = "Enter 6-digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            keyListener = DigitsKeyListener.getInstance("0123456789")
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }
        card.addView(input)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        val verifyBtn = TextView(this).apply {
            text = "Verify"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginStart = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val pin = input.text.toString().trim()
                if (!pin.matches(Regex("^\\d{6}$"))) {
                    input.error = "Enter 6 digits"
                    Snackbar.make(binding.root, "Please enter a valid 6-digit PIN.", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                verifyGuestDeletePin(user, pin)
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(verifyBtn)
        card.addView(buttonRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.5f }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun verifyGuestDeletePin(user: User, pin: String) {
        val userId = user.id ?: return
        val email = user.email.ifBlank { return }

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.verifyGuestDeletionPin(
                    VerifyGuestDeletionPinRequest(
                        userId = userId,
                        email = email,
                        pin = pin
                    )
                )
                if (!response.isSuccessful || response.body()?.get("success") != true) {
                    val message = response.body()?.get("message")?.toString() ?: "Invalid or expired PIN"
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                lastVerifiedGuestDeletionPin = pin
                showGuestDeleteFinalConfirm(user)
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error verifying PIN: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showGuestDeleteFinalConfirm(user: User) {
        val dialog = Dialog(this)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(28), dp(30), dp(28), dp(24))
        }

        val iconCircle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(14)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#FEE2E2"))
            }
        }
        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
        }
        iconCircle.addView(iconView)
        card.addView(iconCircle)

        card.addView(TextView(this).apply {
            text = "Final Confirmation"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        })

        card.addView(TextView(this).apply {
            text = "This will permanently delete your guest account. Continue?"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(22) }
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).also { it.marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }

        val deleteBtn = TextView(this).apply {
            text = "Delete Permanently"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#B91C1C"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).also { it.marginStart = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                deleteGuestAccountNow(user)
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(deleteBtn)
        card.addView(buttonRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.5f }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun deleteGuestAccountNow(user: User) {
        val userId = user.id ?: run {
            Snackbar.make(binding.root, "Invalid session. Please log in again.", Snackbar.LENGTH_LONG).show()
            return
        }
        val email = user.email.ifBlank {
            Snackbar.make(binding.root, "Missing account email.", Snackbar.LENGTH_LONG).show()
            return
        }
        val verifiedPin = lastVerifiedGuestDeletionPin
        if (verifiedPin.isNullOrBlank()) {
            Snackbar.make(binding.root, "PIN verification is required.", Snackbar.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.deleteGuestAccount(
                    userId,
                    mapOf("email" to email, "pin" to verifiedPin)
                )
                if (!response.isSuccessful || response.body()?.get("success") != true) {
                    val message = response.body()?.get("message")?.toString() ?: "Failed to delete account"
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    return@launch
                }

                lastVerifiedGuestDeletionPin = null
                performLogout()
                finish()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error deleting account: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showProfileEditDialog(preloadedUser: User? = null) {
        val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val userId = sharedPrefs.getInt("user_id", -1)
        if (userId <= 0) { Snackbar.make(binding.root, "Please log in again", Snackbar.LENGTH_SHORT).show(); return }

        val dp = resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()

        // ── Root card (white, rounded) ─────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(16).toFloat()
                setColor(Color.WHITE)
            }
            clipToOutline = true
        }

        // ── Coloured header strip ──────────────────────────────────────────
        val headerStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B4332"))
            setPadding(px(20), px(18), px(20), px(18))
        }
        val tvDialogTitle = TextView(this).apply {
            text = "Edit Profile"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val tvDialogSub = TextView(this).apply {
            text = "Fill in the missing information to unlock document requests."
            textSize = 12f
            setTextColor(Color.parseColor("#A7F3D0"))
            setPadding(0, px(4), 0, 0)
        }
        headerStrip.addView(tvDialogTitle)
        headerStrip.addView(tvDialogSub)
        root.addView(headerStrip)

        // ── Scrollable form ────────────────────────────────────────────────
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(4))
        }

        // Helper: section label  (icon + name)
        fun makeLabel(icon: String, text: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(4))
            if (icon.isNotBlank()) {
                addView(TextView(this@ProfileActivity).apply {
                    this.text = icon
                    textSize = 14f
                    setPadding(0, 0, px(6), 0)
                })
            }
            addView(TextView(this@ProfileActivity).apply {
                this.text = text
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#1e293b"))
            })
        }

        // Helper: styled EditText
        fun makeField(hint: String, value: String?, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT) =
            android.widget.EditText(this).apply {
                this.hint = hint
                setHintTextColor(Color.parseColor("#94a3b8"))
                setText(value?.takeIf { it.isNotBlank() } ?: "")
                this.inputType = inputType
                textSize = 14f
                setTextColor(Color.parseColor("#0f172a"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px(8).toFloat()
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(px(1), Color.parseColor("#CBD5E1"))
                }
                setPadding(px(14), px(11), px(14), px(11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

        // Helper: thin divider
        fun makeDivider() = View(this).apply {
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(1)).also { it.topMargin = px(12) }
        }

        // ── Contact number ─────────────────────────────────────────────────
        form.addView(makeLabel("", "Contact Number"))
        val etContact = makeField("e.g. 09171234567", preloadedUser?.contact_number,
            android.text.InputType.TYPE_CLASS_PHONE)
        etContact.filters = arrayOf(InputFilter.LengthFilter(11))
        etContact.keyListener = DigitsKeyListener.getInstance("0123456789")
        form.addView(etContact)
        form.addView(makeDivider())

        // ── Gender ────────────────────────────────────────────────────────
        form.addView(makeLabel("", "Gender"))
        val genderOptions = arrayOf("Select gender…", "Male", "Female", "Non-binary", "Prefer not to say")
        val spinnerGender = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@ProfileActivity,
                android.R.layout.simple_spinner_dropdown_item, genderOptions)
            val cur = preloadedUser?.gender?.takeIf { it.isNotBlank() }
            val idx = genderOptions.indexOfFirst { it.equals(cur, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
            setSelection(idx)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(8).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(px(1), Color.parseColor("#CBD5E1"))
            }
            setPadding(px(10), px(4), px(10), px(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        form.addView(spinnerGender)
        form.addView(makeDivider())

        // ── Birthday ──────────────────────────────────────────────────────
        form.addView(makeLabel("", "Birthday"))
        var selectedBirthdayIso: String? = preloadedUser?.birthday?.takeIf { it.isNotBlank() }
        val tvBirthday = TextView(this).apply {
            val cur = preloadedUser?.birthday?.takeIf { it.isNotBlank() }
            text = if (cur != null) formatBirthday(cur) else "Tap to pick a date"
            setTextColor(if (cur != null) Color.parseColor("#0f172a") else Color.parseColor("#94a3b8"))
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(8).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(px(1), Color.parseColor("#CBD5E1"))
            }
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true; isFocusable = true
        }
        tvBirthday.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            selectedBirthdayIso?.let { iso ->
                try {
                    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .parse(iso.substring(0, 10))
                    if (d != null) cal.time = d
                } catch (_: Exception) {}
            }
            android.app.DatePickerDialog(this, { _, y, m, d ->
                selectedBirthdayIso = "%04d-%02d-%02d".format(y, m + 1, d)
                tvBirthday.text = "%s %d, %d".format(
                    java.text.DateFormatSymbols().months[m], d, y)
                tvBirthday.setTextColor(Color.parseColor("#0f172a"))
            }, cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)).also {
                    it.datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        form.addView(tvBirthday)
        form.addView(makeDivider())

        // ── Address ───────────────────────────────────────────────────────
        form.addView(makeLabel("", "Address"))
        val etAddress = makeField("House / Barangay / City", preloadedUser?.address,
            android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        etAddress.filters = arrayOf(InputFilter.LengthFilter(300))
        etAddress.minLines = 2
        etAddress.gravity = android.view.Gravity.TOP
        form.addView(etAddress)

        // bottom padding inside form
        form.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(16))
        })

        // ── Wrap form in a scroll view with max height ────────────────────
        val maxH = (resources.displayMetrics.heightPixels * 0.60f).toInt()
        val scrollView = android.widget.ScrollView(this).apply {
            addView(form)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(scrollView)

        // ── Bottom action bar ──────────────────────────────────────────────
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(px(16), px(12), px(16), px(16))
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }
        val btnCancel = TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(px(18), px(10), px(18), px(10))
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(6).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
        }
        val btnSave = TextView(this).apply {
            text = "Save Changes"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(px(22), px(10), px(22), px(10))
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = px(6).toFloat()
                setColor(Color.parseColor("#1B4332"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = px(10)
            }
        }
        actionBar.addView(btnCancel)
        actionBar.addView(btnSave)
        root.addView(actionBar)

        // ── Build dialog ───────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()

        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val contact  = etContact.text.toString().trim()
            val gender   = spinnerGender.selectedItem?.toString()
                ?.takeIf { it != "Select gender…" } ?: ""
            val birthday = selectedBirthdayIso ?: ""
            val address  = etAddress.text.toString().trim()
            val mobile = contact.filter { it.isDigit() }
            val addressAllowedRegex = Regex("^[A-Za-z0-9\\s.,#\\-\\/()']+$")

            if (!mobile.matches(Regex("^\\d{11}$"))) {
                etContact.error = "Mobile number must be exactly 11 digits"
                return@setOnClickListener
            }
            if (gender.isBlank())   { Snackbar.make(binding.root, "Please select a gender", Snackbar.LENGTH_SHORT).show(); return@setOnClickListener }
            if (birthday.isBlank()) { Snackbar.make(binding.root, "Please pick your birthday", Snackbar.LENGTH_SHORT).show(); return@setOnClickListener }
            val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            if (birthday > todayIso) {
                Snackbar.make(binding.root, "Birthday cannot be in the future", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (address.isBlank())  { etAddress.error = "Required"; return@setOnClickListener }
            if (address.length > 300) { etAddress.error = "Address must be 300 characters or less"; return@setOnClickListener }
            if (!addressAllowedRegex.matches(address)) {
                etAddress.error = "Address has invalid characters"
                Snackbar.make(binding.root, "Use letters, numbers, spaces, and . , # - / ( ) only.", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            btnSave.isClickable = false
            btnSave.text = "Saving…"
            btnSave.alpha = 0.7f

            lifecycleScope.launch {
                try {
                    val body = mapOf(
                        "contact_number" to mobile,
                        "gender"         to gender,
                        "birthday"       to birthday,
                        "address"        to address
                    )
                    val resp = RetrofitClient.apiService.updateProfileById(userId, body)
                    if (resp.isSuccessful && resp.body()?.get("success") != false) {
                        getSharedPreferences("auth", MODE_PRIVATE).edit().apply {
                            putString("user_contact_number", mobile)
                            putString("user_gender", gender)
                            putString("user_birthday", birthday)
                            putString("user_address", address)
                            apply()
                        }
                        dialog.dismiss()
                        Snackbar.make(binding.root, "Profile updated!", Snackbar.LENGTH_SHORT).show()
                        loadUserProfile()
                    } else {
                        val bodyMsg = resp.body()?.get("message")?.toString()
                        val msg = if (!bodyMsg.isNullOrBlank()) {
                            bodyMsg
                        } else {
                            try {
                                val raw = resp.errorBody()?.string().orEmpty()
                                val json = org.json.JSONObject(raw)
                                json.optString("error").ifBlank {
                                    json.optString("message").ifBlank { "Update failed" }
                                }
                            } catch (_: Exception) {
                                "Update failed"
                            }
                        }
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                        btnSave.isClickable = true; btnSave.text = "Save Changes"; btnSave.alpha = 1f
                    }
                } catch (e: Exception) {
                    Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                    btnSave.isClickable = true; btnSave.text = "Save Changes"; btnSave.alpha = 1f
                }
            }
        }

        dialog.show()

        // Measure after layout; cap only if content exceeds max height.
        scrollView.post {
            val measuredH = form.height
            if (measuredH > maxH) {
                scrollView.layoutParams = (scrollView.layoutParams as LinearLayout.LayoutParams).also {
                    it.height = maxH
                }
                scrollView.requestLayout()
            }
        }
    }

    private fun formatBirthday(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        // Try ISO datetime first (e.g. "2000-01-15T00:00:00.000Z"), then plain date "2000-01-15"
        val parsers = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("MM/dd/yyyy", Locale.US),
        )
        for (parser in parsers) {
            try {
                parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = parser.parse(raw.trim()) ?: continue
                return SimpleDateFormat("MMMM d, yyyy", Locale.US).format(date)
            } catch (_: Exception) {}
        }
        return raw  // fallback: show as-is if nothing matched
    }


}
