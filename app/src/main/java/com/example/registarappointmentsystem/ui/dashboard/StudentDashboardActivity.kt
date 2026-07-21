package com.example.registarappointmentsystem.ui.dashboard

import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.print.PrintManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.text.InputFilter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.app.Dialog
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.example.registarappointmentsystem.utils.SessionManager
import com.example.registarappointmentsystem.utils.NotificationHelper
import com.example.registarappointmentsystem.utils.BackgroundSyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.google.android.material.snackbar.Snackbar
import java.util.Calendar


class StudentDashboardActivity : AppCompatActivity() {

    companion object {
        private const val PAYMENT_CHANNEL_ID = "digireg_payment"
        private const val PAYMENT_NOTIF_BASE_ID = 2000
    }

    private lateinit var binding: ActivityStudentDashboardBinding
    private lateinit var appointmentRepository: AppointmentRepositoryImpl
    private lateinit var appointmentsAdapter: AppointmentsAdapter
    private lateinit var sessionManager: SessionManager
    private lateinit var notificationHelper: NotificationHelper
    private var autoRefreshJob: Job? = null
    // Guard flag: prevents the OnItemSelectedListener from reacting when we
    // programmatically reset the selected nav item in onResume
    private var restoringNavSelection = false

    // Payment screenshot fields
    private var currentPaymentScreenshotUri: Uri? = null
    private lateinit var screenshotLauncher: ActivityResultLauncher<Array<String>>
    // Callback set before launching the picker so the dialog can react
    private var onScreenshotPicked: ((Uri) -> Unit)? = null
    private var registrarEmail: String = "registrar@upang.edu.ph"
    private var registrarPhone: String = "(075) 123-4567"
    private var registrarMobile: String = "0917-000-0000"
    private var gcashNumber: String = "0917-123-4567"
    private var gcashName: String = "Juan D. Registrar"
    private var bdoNumber: String = "1234-5678-9012"
    private var bdoName: String = "PHINMA UPang Registrar Office"
    
    // Permission request launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result will be handled automatically by NotificationManagerCompat
    }

    /** Returns true only when a valid signed-in user exists in local auth storage. */
    private fun isAuthenticated(): Boolean {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val userId = prefs.getInt("user_id", -1)
        // App-wide auth checks use user_id; email may be absent for some flows.
        return userId > 0
    }

    /** Blocks dashboard access when the user is logged out (e.g., notification tap). */
    private fun enforceAuthOrRedirect(): Boolean {
        if (isAuthenticated()) return true
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
        return false
    }

    private fun enforcePasswordPolicyOrRedirect(): Boolean {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val mustChangePassword = prefs.getBoolean("must_change_password", false)
        if (!mustChangePassword) return true
        startActivity(Intent(this, ProfileActivity::class.java).apply {
            putExtra("forcePasswordChange", true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
        return false
    }


    override fun onResume() {
        super.onResume()

        if (!enforceAuthOrRedirect()) return
        if (!enforcePasswordPolicyOrRedirect()) return
        
        binding.contentLayout.alpha = 1f
        // Re-select the correct nav tab without triggering navigation
        restoringNavSelection = true
        binding.bottomNavigation.selectedItemId = R.id.nav_dashboard
        restoringNavSelection = false
        lifecycleScope.launch { syncNotificationPrefsFromServer() }
        loadRegistrarContactSettings()
        loadAppointments()
        refreshBanner()
        // Poll the server silently every 3 seconds while the screen is visible
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
        if (!enforcePasswordPolicyOrRedirect()) return

        // Initialize ViewBinding
        binding = ActivityStudentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize session and notification managers
        sessionManager = SessionManager(this)
        notificationHelper = NotificationHelper(this)
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Register image-picker launcher (must be done before onStart)
        screenshotLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                if (!isAllowedPaymentProofType(uri)) {
                    Snackbar.make(binding.root, "Only JPG and PNG proof images are allowed.", Snackbar.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                currentPaymentScreenshotUri = uri
                onScreenshotPicked?.invoke(uri)
            }
        }

        // Initialize repository
        appointmentRepository = AppointmentRepositoryImpl(RetrofitClient.apiService)
        loadRegistrarContactSettings()

        initViews()
        setupNavigation()
        setupRecyclerView()
        setupAnimations()
        setupNotificationChannel()
        setupActivityTracking()
        lifecycleScope.launch { syncNotificationPrefsFromServer() }
        loadAppointments()
    }

    /**
     * Setup touch listeners to track user activity for session timeout
     */
    private fun setupActivityTracking() {
        binding.root.setOnTouchListener { _, _ ->
            // Keep tracking user interactions for responsiveness
            false
        }
    }

    /** Creates the notification channel required on Android 8+ */
    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PAYMENT_CHANNEL_ID,
                "Payment Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a payment is required before documents are processed"
                enableLights(true)
                lightColor = Color.YELLOW
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /**
     * For each appointment that requires payment (paymentAmount set, paymentStatus == "pending"),
     * post a system notification once and remember it in SharedPreferences so it isn't repeated.
     */
    private fun checkAndNotifyPendingPayments(appointments: List<Appointment>) {
        val prefs = getSharedPreferences("payment_notifs", MODE_PRIVATE)
        val userId = getSharedPreferences("auth", MODE_PRIVATE).getInt("user_id", -1)
        val terminalStatuses = setOf("completed", "cancelled", "no_show", "rejected")
        val pendingActive = appointments
            .filter {
                val amountPending = (it.paymentAmount?.toDoubleOrNull() ?: 0.0) > 0.0
                val paymentPending = it.paymentStatus?.equals("pending", ignoreCase = true) == true
                val activeStatus = (it.status ?: "").lowercase() !in terminalStatuses
                amountPending && paymentPending && activeStatus
            }

        val initKey = "payment_notifs_initialized_$userId"
        if (!prefs.getBoolean(initKey, false)) {
            val initEditor = prefs.edit()
            pendingActive.forEach { appt ->
                initEditor.putBoolean("notified_${userId}_${appt.id}", true)
            }
            initEditor.putBoolean(initKey, true).apply()
            return
        }

        pendingActive
            .forEach { appt ->
                val key = "notified_${userId}_${appt.id}"
                if (!prefs.getBoolean(key, false)) {
                    val amount = "₱${String.format("%.2f", appt.paymentAmount!!.toDouble())}"
                    val intent = Intent(this, StudentDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    val pi = PendingIntent.getActivity(
                        this, appt.id ?: 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val notif = NotificationCompat.Builder(this, PAYMENT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("💳 Payment Required – DigiReg")
                        .setContentText("$amount due for \"${appt.purpose}\". ${appt.getDeadlineRemainingText() ?: "Submit proof now."}")
                        .setStyle(NotificationCompat.BigTextStyle()
                            .bigText("A payment of $amount is required for your \"${appt.purpose}\" request before your documents can be processed. ${appt.getDeadlineRemainingText()?.let { "$it — failure to submit will result in automatic cancellation." } ?: "Open the app and submit your GCash proof."}"))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build()
                    try {
                        NotificationManagerCompat.from(this).notify(
                            PAYMENT_NOTIF_BASE_ID + (appt.id ?: 0), notif
                        )
                        prefs.edit().putBoolean(key, true).apply()
                    } catch (_: SecurityException) {
                        // POST_NOTIFICATIONS not granted on Android 13+ — silently skip
                    }
                }
            }
    }

    /**
     * Detects status and payment changes for appointments and sends appropriate notifications
     * using NotificationHelper methods. Stores previous state in SharedPreferences to detect deltas.
     */
    private fun checkStatusChangesAndNotify(appointments: List<Appointment>) {
        val cache = getSharedPreferences("appointment_status_cache", MODE_PRIVATE)
        val cacheEditor = cache.edit()
        val statusUpdatesEnabled = getSharedPreferences("auth", MODE_PRIVATE).getBoolean("pref_status_updates", true)
        val readyPickupEnabled = getSharedPreferences("auth", MODE_PRIVATE).getBoolean("pref_ready_pickup", true)
        
        appointments.forEach { appt ->
            val apptId = appt.id ?: return@forEach
            val cacheKeyStatus = "status_$apptId"
            val cacheKeyPaymentStatus = "payment_status_$apptId"
            val cacheKeyPaymentAmount = "payment_amount_$apptId"
            val cacheKeyReadyDate = "ready_date_$apptId"
            
            val prevStatus = cache.getString(cacheKeyStatus, null)
            val prevPaymentStatus = cache.getString(cacheKeyPaymentStatus, null)
            val prevPaymentAmount = cache.getString(cacheKeyPaymentAmount, null)
            val prevReadyDate = cache.getString(cacheKeyReadyDate, null)

            val prevPayAmt = prevPaymentAmount?.toDoubleOrNull() ?: 0.0
            val currPayAmt = appt.paymentAmount?.toDoubleOrNull() ?: 0.0
            
            // Initialize cache on first load: store current state
            if (prevStatus == null) {
                cacheEditor.putString(cacheKeyStatus, appt.status)
                cacheEditor.putString(cacheKeyPaymentStatus, appt.paymentStatus)
                cacheEditor.putString(cacheKeyPaymentAmount, appt.paymentAmount)
                cacheEditor.putString(cacheKeyReadyDate, appt.ready_date)
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
            cacheEditor.putString(cacheKeyReadyDate, appt.ready_date)
        }
        
        cacheEditor.apply()
    }

    private fun initViews() {
        // Set toolbar title and greeting from SharedPreferences
        binding.textToolbarTitle.text = "My Dashboard"
        val sharedPrefsInit = getSharedPreferences("auth", MODE_PRIVATE)
        val firstName = sharedPrefsInit.getString("user_first_name", null)
        binding.textWelcomeUser.text = if (!firstName.isNullOrBlank()) "Welcome back, $firstName!" else "Welcome back,"

        // Banner wired here — visibility is driven by refreshBanner() so it also updates on onResume
        val bannerClickListener = View.OnClickListener {
            val intent = Intent(this, ProfileActivity::class.java).apply {
                putExtra("isProfileIncomplete", true)
            }
            startActivity(intent)
        }
        binding.bannerProfileIncomplete.setOnClickListener(bannerClickListener)
        binding.bannerProfileCompleteBtn.setOnClickListener(bannerClickListener)

        // Style notification badge
        binding.textNotifBadge.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.parseColor("#EF4444"))
        }
        // Notification bell
        binding.buttonNotification.setOnClickListener {
            showNotifications()
        }
        // Floating help button — shows contact dialog
        binding.buttonHelp.setOnClickListener {
            showContactDialog()
        }
        binding.buttonRequestCredentials?.setOnClickListener {
            navigateWithAnimation(RequestCredentialsActivity::class.java)
        }
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
        val icon = android.widget.ImageView(this).apply {
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
            startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$email")
            })
        }
        contactsLayout.addView(emailBtn)

        val landlineBtn = createContactButton(
            phone, "Landline", "TEL", Color.parseColor("#E0E7FF"),
            Color.parseColor("#F8FAFC"), Color.parseColor("#C7D2FE")
        ) {
            startActivity(Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${phone.replace(Regex("[^\\d+]"), "")}")
            })
        }
        contactsLayout.addView(landlineBtn)

        val mobileBtn = createContactButton(
            mobile, "Mobile", "SMS", Color.parseColor("#DCFCE7"),
            Color.parseColor("#F8FAFC"), Color.parseColor("#86EFAC")
        ) {
            startActivity(Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${mobile.replace(Regex("[^\\d+]"), "")}")
            })
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
            text = "Done"
            textSize = 15f
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

    private fun loadRegistrarContactSettings(onLoaded: (() -> Unit)? = null) {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getSettings()
                if (!response.isSuccessful) return@launch
                val body = response.body() ?: return@launch
                val settings = body["settings"] as? Map<*, *> ?: return@launch

                registrarEmail = settings["registrar_email"]?.toString()?.takeIf { it.isNotBlank() } ?: registrarEmail
                registrarPhone = settings["registrar_phone"]?.toString()?.takeIf { it.isNotBlank() } ?: registrarPhone
                registrarMobile = settings["registrar_mobile"]?.toString()?.takeIf { it.isNotBlank() } ?: registrarMobile
                gcashNumber = settings["gcash_number"]?.toString()?.takeIf { it.isNotBlank() } ?: gcashNumber
                gcashName = settings["gcash_name"]?.toString()?.takeIf { it.isNotBlank() } ?: gcashName
                bdoNumber = settings["bdo_number"]?.toString()?.takeIf { it.isNotBlank() } ?: bdoNumber
                bdoName = settings["bdo_name"]?.toString()?.takeIf { it.isNotBlank() } ?: bdoName
                onLoaded?.invoke()
            } catch (e: Exception) {
                Log.w("StudentDashboard", "Failed to load registrar contact settings", e)
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
            setPadding(16, 16, 16, 16)
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
            layoutParams = LinearLayout.LayoutParams(48, 48).also { it.marginEnd = 16 }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(iconBg)
            }
        }
        val iconTV = TextView(this).apply {
            setText(badge)
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1E3A8A"))
            gravity = Gravity.CENTER
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

    private fun refreshBanner() {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val incomplete = prefs.getString("user_contact_number", "").isNullOrBlank() ||
            prefs.getString("user_gender", "").isNullOrBlank() ||
            prefs.getString("user_birthday", "").isNullOrBlank() ||
            prefs.getString("user_address", "").isNullOrBlank()
        binding.bannerProfileIncomplete.visibility = if (incomplete) View.VISIBLE else View.GONE
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (restoringNavSelection) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_dashboard -> true // Already here
                R.id.nav_request_credentials -> {
                    navigateWithAnimation(RequestCredentialsActivity::class.java)
                    false
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
                    overridePendingTransition(0, 0)
                    false
                }
                R.id.nav_logout -> {
                    showLogoutConfirmation()
                    false
                }
                else -> false
            }
        }
        // Only set selectedItemId in onCreate, not after navigation
        // (prevents always reverting to dashboard tab)
        if (intent.getBooleanExtra("fromProfile", false)) {
            binding.bottomNavigation.selectedItemId = R.id.nav_profile
        } else {
            binding.bottomNavigation.selectedItemId = R.id.nav_dashboard
        }
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

        // Pull-to-refresh
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.primary_dark_green,
            R.color.secondary_dark_gold_dark
        )
        // Push the spinner below the initial padding so it shows above the first card
        binding.swipeRefreshLayout.setProgressViewOffset(false, 0, 160)
        binding.swipeRefreshLayout.setOnRefreshListener {
            loadAppointments()
        }
    }

    /** Background poll — no spinner so the UI doesn't flash every 30 s. */
    private fun refreshSilently() {
        lifecycleScope.launch {
            try {
                syncNotificationPrefsFromServer()
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
                syncNotificationPrefsFromServer()
                // Get current user ID from SharedPreferences
                val sharedPrefs = getSharedPreferences("auth", MODE_PRIVATE)
                val userId = sharedPrefs.getInt("user_id", -1)
                
                val appointments = if (userId > 0) {
                    appointmentRepository.getAppointmentsForUser(userId)
                } else {
                    emptyList()
                }

                
                binding.progressBar.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false

                if (appointments.isEmpty()) {
                    showEmptyState()
                } else {
                    showAppointments(appointments)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
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
        checkAndNotifyPendingPayments(appointments)
        checkStatusChangesAndNotify(appointments)
        updateNotifBadge(appointments)
    }

    private fun updateNotifBadge(appointments: List<Appointment>) {
        val filtered = filterClearedNotifications(appointments)
        val active = filtered.count { a ->
            a.status?.lowercase() !in listOf("completed", "cancelled", "no_show", "rejected")
        }
        if (active > 0) {
            binding.textNotifBadge.text = if (active > 99) "99+" else active.toString()
            binding.textNotifBadge.visibility = View.VISIBLE
        } else {
            binding.textNotifBadge.visibility = View.GONE
        }
    }

    private fun filterClearedNotifications(appointments: List<Appointment>): List<Appointment> {
        val clearedAt = getSharedPreferences("auth", MODE_PRIVATE).getLong("notifications_cleared_at", 0L)
        if (clearedAt == 0L) return appointments
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
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
        updateNotifBadge(emptyList())
    }

    private fun showCancelConfirmation(appointment: Appointment) {
        val dialog = Dialog(this)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
        }

        val iconCircle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).also {
                it.bottomMargin = dp(12)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#FEE2E2"))
            }
        }
        iconCircle.addView(android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_alert)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        })
        card.addView(iconCircle)

        card.addView(TextView(this).apply {
            text = "Cancel Request?"
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = "This will cancel your request for \"${appointment.purpose ?: "document"}\". You can submit a new request anytime."
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val keepBtn = TextView(this).apply {
            text = "Keep"
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginEnd = dp(8) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        val cancelBtn = TextView(this).apply {
            text = "Yes, Cancel"
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#DC2626"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                cancelAppointment(appointment)
            }
        }
        actionRow.addView(keepBtn)
        actionRow.addView(cancelBtn)
        card.addView(actionRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
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

        val readyDateYmd = normalizeReadyDateYmd(appointment.ready_date)
        val readyDateCal = readyDateYmd?.let { parseYmdToCalendar(it) }

        // If payment is required and not yet verified, show payment dialog instead of date picker
        val paymentAmount = appointment.paymentAmount?.toDoubleOrNull()
        if (paymentAmount != null && paymentAmount > 0 && appointment.paymentStatus != "verified") {
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
                    picked.set(Calendar.HOUR_OF_DAY, 0)
                    picked.set(Calendar.MINUTE, 0)
                    picked.set(Calendar.SECOND, 0)
                    picked.set(Calendar.MILLISECOND, 0)
                    val isoDate = String.format("%04d-%02d-%02d", year, month + 1, day)
                    val dow = picked.get(Calendar.DAY_OF_WEEK)
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                        Snackbar.make(binding.root, "Weekends are not available. Please select a weekday.", Snackbar.LENGTH_LONG).show()
                        openPicker()
                        return@DatePickerDialog
                    }
                    if (readyDateYmd != null && isoDate < readyDateYmd) {
                        val readyLabel = readyDateCal?.let {
                            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(it.time)
                        } ?: readyDateYmd
                        Snackbar.make(binding.root, "Cannot book before $readyLabel. Please select a date on or after the available date.", Snackbar.LENGTH_LONG).show()
                        openPicker()
                        return@DatePickerDialog
                    }

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

                    showTimeSlotDialog(fetchedSlots, displayDate, isoDate, appointmentId, readyDateYmd)
                }
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).apply {
                val todayMin = System.currentTimeMillis() - 1000
                val readyMin = readyDateCal?.timeInMillis
                datePicker.minDate = if (readyMin != null && readyMin > todayMin) readyMin else todayMin
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

        val tvGcashDetails = dialogView.findViewById<TextView>(R.id.tvGcashDetails)
        val tvBdoDetails = dialogView.findViewById<TextView>(R.id.tvBdoDetails)
        tvGcashDetails.text = "$gcashNumber · $gcashName"
        tvBdoDetails.text = "$bdoNumber · $bdoName"

        // — Header —
        dialogView.findViewById<TextView>(R.id.tvPaymentPurpose).text =
            appointment.purpose ?: "Document request"

        // — Amount Due —
        dialogView.findViewById<TextView>(R.id.tvAmountDue).text = run {
            val deadlineText = appointment.getDeadlineRemainingText()
            if (deadlineText != null) "$amtStr\n$deadlineText" else amtStr
        }

        // — Rejected warning —
        if (payStat == "rejected") {
            dialogView.findViewById<View>(R.id.layoutRejectedWarning).visibility = View.VISIBLE
        }

        // — Close button —
        dialogView.findViewById<View>(R.id.btnPaymentClose).setOnClickListener { dialog.dismiss() }

        // Refresh payment account details in case registrar settings changed while app stays open.
        loadRegistrarContactSettings {
            tvGcashDetails.text = "$gcashNumber · $gcashName"
            tvBdoDetails.text = "$bdoNumber · $bdoName"
        }

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
            screenshotLauncher.launch(arrayOf("image/jpeg", "image/png"))
        }

        // — Submit button —
        val btnSubmit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSubmitPayment)
        val refInput  = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRefNumber)
        val refLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilRefNumber)

        refInput.filters = arrayOf(InputFilter.LengthFilter(11))
        refInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val digitsOnly = s?.toString()?.replace(Regex("\\D"), "") ?: ""
                if (digitsOnly != s?.toString()) {
                    refInput.setText(digitsOnly)
                    refInput.setSelection(digitsOnly.length)
                }
                refLayout.error = null
            }
        })

        btnSubmit.setOnClickListener {
            val ref = refInput.text.toString().trim()
            if (ref.isEmpty()) {
                refLayout.error = "Reference number is required."
                return@setOnClickListener
            }
            if (!ref.matches(Regex("^\\d{11}$"))) {
                refLayout.error = "Invalid reference number. Use exactly 11 digits (0-9)."
                return@setOnClickListener
            }
            refLayout.error = null

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
        appointmentId: Int,
        readyDateYmd: String?
    ) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_slot_picker, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.tvPickerDate).text = displayDate
        view.findViewById<View>(R.id.btnPickerClose).setOnClickListener { dialog.dismiss() }

        // Sort incoming slots defensively because backend order may vary.
        fun timeToMinutes(timeLabel: String): Int {
            val parts = timeLabel.trim().split(" ")
            if (parts.size != 2) return Int.MAX_VALUE
            val hm = parts[0].split(":")
            if (hm.size != 2) return Int.MAX_VALUE
            val hour12 = hm[0].toIntOrNull() ?: return Int.MAX_VALUE
            val minute = hm[1].toIntOrNull() ?: return Int.MAX_VALUE
            val ampm = parts[1].uppercase()
            val hour24 = when {
                ampm == "AM" && hour12 == 12 -> 0
                ampm == "AM" -> hour12
                ampm == "PM" && hour12 == 12 -> 12
                ampm == "PM" -> hour12 + 12
                else -> return Int.MAX_VALUE
            }
            return hour24 * 60 + minute
        }

        val sortedSlots = slots.sortedBy { timeToMinutes(it.time) }
        val morningSlots = sortedSlots.filter { "AM" in it.time || it.time == "12:00 PM" }
        val afternoonSlots = sortedSlots.filter { "PM" in it.time && it.time != "12:00 PM" }

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
                                showPickupScheduleConfirmation(displayDate, slot.time) {
                                    if (readyDateYmd != null && isoDate < readyDateYmd) {
                                        Snackbar.make(binding.root, "Cannot book before the registrar's available date.", Snackbar.LENGTH_LONG).show()
                                        return@showPickupScheduleConfirmation
                                    }
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

    private fun showPickupScheduleConfirmation(
        displayDate: String,
        displayTime: String,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(this)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(22), dp(20), dp(22), dp(16))
        }

        val title = TextView(this).apply {
            text = "Confirm Pickup Schedule"
            textSize = 18f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Please verify your selected pickup date and time."
            textSize = 12.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        }
        card.addView(title)
        card.addView(subtitle)

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        summary.addView(TextView(this).apply {
            text = "Date: $displayDate"
            textSize = 14f
            setTextColor(Color.parseColor("#1E3A8A"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        summary.addView(TextView(this).apply {
            text = "Time: $displayTime"
            textSize = 14f
            setTextColor(Color.parseColor("#1E40AF"))
            setPadding(0, dp(4), 0, 0)
        })
        summary.addView(TextView(this).apply {
            text = "Please arrive 5 minutes early. Late arrivals may be marked no-show."
            textSize = 12f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(summary)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            layoutParams = lp
        }

        val changeBtn = Button(this).apply {
            text = "Change"
            setTextColor(Color.parseColor("#334155"))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        val confirmBtn = Button(this).apply {
            text = "Confirm"
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        val leftLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        leftLp.marginEnd = dp(6)
        buttonRow.addView(changeBtn, leftLp)
        buttonRow.addView(confirmBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(buttonRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.55f }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun isAllowedPaymentProofType(uri: Uri): Boolean {
        val mime = contentResolver.getType(uri)?.lowercase() ?: return false
        return mime == "image/jpeg" || mime == "image/jpg" || mime == "image/png"
    }

    private fun parseYmdToCalendar(ymd: String): Calendar? {
        return try {
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(ymd)
            Calendar.getInstance().apply {
                time = parsed ?: return null
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeReadyDateYmd(rawReadyDate: String?): String? {
        val raw = rawReadyDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        fun toLocalYmd(date: java.util.Date): String {
            val cal = Calendar.getInstance().apply { time = date }
            return String.format(
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        }

        val isoWithTimePatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in isoWithTimePatterns) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                if (pattern.endsWith("'Z'")) {
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(raw)
                if (parsed != null) return toLocalYmd(parsed)
            } catch (_: Exception) {
                // Try next pattern.
            }
        }

        val ymdMatch = Regex("(\\d{4}-\\d{2}-\\d{2})").find(raw)?.groupValues?.get(1)
        if (ymdMatch != null) return ymdMatch

        return try {
            val parsed = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).parse(raw)
            if (parsed != null) toLocalYmd(parsed) else null
        } catch (_: Exception) {
            null
        }
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
        // SINGLE_TOP prevents duplicate instances when tapping the same nav item twice
        val intent = Intent(this, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
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
        val refId        = appointment.reference_number ?: "—"
        val status       = appointment.status?.uppercase() ?: "—"
        val paymentAmount = appointment.paymentAmount?.toDoubleOrNull() ?: 0.0
        val isFreePayment = paymentAmount <= 0.0
        val isPaymentVerified = appointment.paymentStatus.equals("verified", ignoreCase = true)
        val paymentDetailsRows = if (isFreePayment) {
            "<tr><td>Payment:</td><td><strong>FREE</strong></td></tr>"
        } else if (isPaymentVerified) {
            """
            <tr><td>Payment:</td><td><strong>Verified</strong></td></tr>
            <tr><td>Total Paid:</td><td><strong>₱${"%.2f".format(paymentAmount)}</strong></td></tr>
            """.trimIndent()
        } else {
            "<tr><td>Payment:</td><td>Pending Verification</td></tr>"
        }

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
                                    $paymentDetailsRows
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
            Snackbar.make(binding.root, "Please log in to view notifications.", Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val appointments = appointmentRepository.getAppointmentsForUser(userId)
                val filtered = filterClearedNotifications(appointments)
                showNotificationsBottomSheet(filtered)
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Could not load notifications: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showNotificationsBottomSheet(appointments: List<Appointment>) {
        val sheet = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        // ── Header ──────────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(56, 40, 56, 24)
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = "Notifications"
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
                getSharedPreferences("auth", MODE_PRIVATE).edit()
                    .putLong("notifications_cleared_at", System.currentTimeMillis())
                    .apply()
                updateNotifBadge(emptyList())
                sheet.dismiss()
            }
        }
        header.addView(clearBtn)
        container.addView(header)

        // ── Divider ──────────────────────────────────────────────────────────
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
            // Sort newest first
            val sorted = appointments.sortedByDescending { it.updated_at ?: it.created_at }
            val currentList = appointmentsAdapter.currentList

            // ── Payment-pending entries FIRST (urgent, amber highlight) ────────────
            appointments
                .filter { (it.paymentAmount?.toDoubleOrNull() ?: 0.0) > 0.0 && it.paymentStatus == "pending" }
                .forEach { appt ->
                    val amount = "₱${String.format("%.2f", appt.paymentAmount!!.toDouble())}"
                    val payRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(56, 28, 56, 28)
                        setBackgroundColor(Color.parseColor("#fffbeb"))
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            sheet.dismiss()
                            val apptIndex = currentList.indexOfFirst { it.id == appt.id }
                            if (apptIndex >= 0) {
                                binding.recyclerAppointments.smoothScrollToPosition(apptIndex)
                                binding.recyclerAppointments.postDelayed({
                                    val vh = binding.recyclerAppointments.findViewHolderForAdapterPosition(apptIndex)
                                    (vh?.itemView as? MaterialCardView)?.let { card ->
                                        val originalCardColor = card.cardBackgroundColor.defaultColor
                                        card.setCardBackgroundColor(Color.parseColor("#fef3c7"))
                                        card.postDelayed({ card.setCardBackgroundColor(originalCardColor) }, 2000)
                                    }
                                }, 400)
                            }
                        }
                    }
                    // Amber dot
                    val payDot = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(20, 20).also { it.topMargin = 8; it.marginEnd = 24 }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.parseColor("#f59e0b"))
                        }
                    }
                    payRow.addView(payDot)
                    val payTextBlock = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    payTextBlock.addView(TextView(this).apply {
                        text = "💳  ${appt.purpose ?: "Document request"}"
                        textSize = 14f
                        setTextColor(Color.parseColor("#0f172a"))
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    payTextBlock.addView(TextView(this).apply {
                        text = "Payment of $amount required — tap to submit proof"
                        textSize = 12f
                        setTextColor(Color.parseColor("#b45309"))
                    })
                    payRow.addView(payTextBlock)
                    payRow.addView(TextView(this).apply {
                        text = "→"
                        textSize = 16f
                        setTextColor(Color.parseColor("#f59e0b"))
                        setPadding(16, 0, 0, 0)
                    })
                    container.addView(payRow)
                    container.addView(View(this).apply {
                        setBackgroundColor(Color.parseColor("#fde68a"))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                }

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
                    // Navigate/highlight the card in recyclerView on tap
                    val apptIndex = currentList.indexOfFirst { it.id == appt.id }
                    setOnClickListener {
                        sheet.dismiss()
                        if (apptIndex >= 0) {
                            binding.recyclerAppointments.smoothScrollToPosition(apptIndex)
                            binding.recyclerAppointments.postDelayed({
                                val vh = binding.recyclerAppointments.findViewHolderForAdapterPosition(apptIndex)
                                (vh?.itemView as? MaterialCardView)?.let { card ->
                                    val originalCardColor = card.cardBackgroundColor.defaultColor
                                    card.setCardBackgroundColor(Color.parseColor("#dbeafe"))
                                    card.postDelayed({ card.setCardBackgroundColor(originalCardColor) }, 1800)
                                }
                            }, 400)
                        }
                    }
                }

                // Dot indicator
                val dot = View(this).apply {
                    setBackgroundColor(Color.parseColor(dotColor))
                    layoutParams = LinearLayout.LayoutParams(20, 20).also { it.topMargin = 8; it.marginEnd = 24 }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(dotColor))
                    }
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
                val noteView = appt.admin_comment?.takeIf { it.isNotBlank() }?.let { note ->
                    TextView(this).apply {
                        text = "Note: $note"
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#64748b"))
                        setPadding(0, 4, 0, 0)
                    }
                }
                textBlock.addView(msgView)
                textBlock.addView(statusView)
                if (noteView != null) textBlock.addView(noteView)
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
                val rowDiv = View(this).apply {
                    setBackgroundColor(Color.parseColor("#f1f5f9"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                }
                container.addView(rowDiv)
            }
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }
        val card = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.WHITE)
            }
            clipToOutline = true
            addView(scrollView)
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

    private suspend fun syncNotificationPrefsFromServer(): Pair<Boolean, Boolean> {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val localStatus = prefs.getBoolean("pref_status_updates", true)
        val localReady = prefs.getBoolean("pref_ready_pickup", true)
        val userId = prefs.getInt("user_id", -1)
        if (userId <= 0) return localStatus to localReady

        return try {
            val response = RetrofitClient.apiService.getNotificationPreferences(userId)
            if (!response.isSuccessful) return localStatus to localReady
            val body = response.body() ?: return localStatus to localReady
            val prefMap = body["preferences"] as? Map<*, *>
            val statusUpdates = (prefMap?.get("statusUpdates") as? Boolean) ?: localStatus
            val readyPickup = (prefMap?.get("readyPickup") as? Boolean) ?: localReady
            prefs.edit()
                .putBoolean("pref_status_updates", statusUpdates)
                .putBoolean("pref_ready_pickup", readyPickup)
                .apply()
            statusUpdates to readyPickup
        } catch (_: Exception) {
            localStatus to localReady
        }
    }

    private fun showNotificationPreferencesDialog() {
        lifecycleScope.launch {
            val (statusInitial, readyInitial) = syncNotificationPrefsFromServer()
            val prefs = getSharedPreferences("auth", MODE_PRIVATE)

            val dialog = Dialog(this@StudentDashboardActivity)
            val container = LinearLayout(this@StudentDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(22), dp(22), dp(16))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.WHITE)
                }
            }

            container.addView(TextView(this@StudentDashboardActivity).apply {
                text = "Notification Preferences"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            })
            container.addView(TextView(this@StudentDashboardActivity).apply {
                text = "Control email and in-app appointment updates"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(4), 0, dp(14))
            })

            val statusCard = LinearLayout(this@StudentDashboardActivity).apply {
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
            val statusSwitch = SwitchCompat(this@StudentDashboardActivity).apply {
                isChecked = statusInitial
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BFDBFE"))
            }
            statusCard.addView(LinearLayout(this@StudentDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@StudentDashboardActivity).apply {
                    text = "Status updates"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(this@StudentDashboardActivity).apply {
                    text = "Approved, cancelled, processing, payment updates"
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                })
            })
            statusCard.addView(statusSwitch)
            statusCard.setOnClickListener { statusSwitch.isChecked = !statusSwitch.isChecked }
            container.addView(statusCard)

            container.addView(View(this@StudentDashboardActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            })

            val readyCard = LinearLayout(this@StudentDashboardActivity).apply {
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
            val readySwitch = SwitchCompat(this@StudentDashboardActivity).apply {
                isChecked = readyInitial
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BFDBFE"))
            }
            readyCard.addView(LinearLayout(this@StudentDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@StudentDashboardActivity).apply {
                    text = "Ready for pickup"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(this@StudentDashboardActivity).apply {
                    text = "Notify when documents are ready/for claiming"
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                })
            })
            readyCard.addView(readySwitch)
            readyCard.setOnClickListener { readySwitch.isChecked = !readySwitch.isChecked }
            container.addView(readyCard)

            val buttonRow = LinearLayout(this@StudentDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
            }
            val closeBtn = TextView(this@StudentDashboardActivity).apply {
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
            val saveBtn = TextView(this@StudentDashboardActivity).apply {
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
                        val userId = prefs.getInt("user_id", -1)
                        if (userId > 0) {
                            try {
                                RetrofitClient.apiService.updateNotificationPreferences(
                                    userId,
                                    mapOf("statusUpdates" to statusValue, "readyPickup" to readyValue)
                                )
                            } catch (e: Exception) {
                                Snackbar.make(binding.root, "Saved locally, but server sync failed.", Snackbar.LENGTH_LONG).show()
                                Log.e("StudentDashboard", "Notification preference sync failed", e)
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

        val title = TextView(this).apply {
            text = "Sign Out"
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#0f172a"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) }
        }
        card.addView(title)

        val message = TextView(this).apply {
            text = "Are you sure you want to sign out?"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#64748b"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(24) }
        }
        card.addView(message)

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


    private fun performLogout() {
        // Clear session timeout tracking
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
