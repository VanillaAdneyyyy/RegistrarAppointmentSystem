package com.example.registarappointmentsystem.ui.requests

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.InputFilter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
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
import android.app.Dialog
import com.google.android.material.snackbar.Snackbar
import com.example.registarappointmentsystem.utils.BackgroundSyncScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.registarappointmentsystem.utils.SessionManager
import com.example.registarappointmentsystem.utils.NotificationHelper

class RequestCredentialsActivity : AppCompatActivity() {

    companion object {
        private const val MAX_FORMER_STUDENT_ID_LENGTH = 50
        private val FORMER_STUDENT_ID_FORBIDDEN_CHARS = setOf('^', '$', '#', '&', '*', '(', ')')
        private val FORMER_STUDENT_ID_FORBIDDEN_REGEX = Regex("[\\^\\$#&*()]")
    }

    private lateinit var binding: ActivityRequestCredentialsBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var notificationHelper: NotificationHelper

    private val selectedDocIds = mutableSetOf<Int>()
    private var docTypesList: List<DocumentType> = emptyList()
    private var selectedTotal = 0.0
    private var idPhotoUri: Uri? = null
    private val quickReminders = listOf(
        "Processing takes 3-5 working days.",
        "No pending financial clearances required.",
        "You will be notified once your document is ready for pickup.",
        "Bring a valid ID when claiming your document.",
        "If the requestor is not present during claiming, an authorization letter is required."
    )
    
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

    private fun isProfileComplete(): Boolean {
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        val contact = prefs.getString("user_contact_number", "").orEmpty().trim()
        val gender = prefs.getString("user_gender", "").orEmpty().trim()
        val birthday = prefs.getString("user_birthday", "").orEmpty().trim()
        val address = prefs.getString("user_address", "").orEmpty().trim()
        return contact.isNotEmpty() && gender.isNotEmpty() && birthday.isNotEmpty() && address.isNotEmpty()
    }

    private fun enforceProfileCompleteOrRedirect(): Boolean {
        if (isProfileComplete()) return true
        Snackbar.make(
            binding.root,
            "Please complete your profile before requesting credentials.",
            Snackbar.LENGTH_LONG
        ).show()
        startActivity(Intent(this, ProfileActivity::class.java).apply {
            putExtra("isProfileIncomplete", true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
        return false
    }

    /** Image picker launcher — registered before onCreate as required by Activity Result API */
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            idPhotoUri = uri
            binding.imageIdPhotoPreview.setImageURI(uri)
            binding.imageIdPhotoPreview.visibility = View.VISIBLE
            binding.buttonRemoveIdPhoto.visibility = View.VISIBLE
            binding.buttonPickIdPhoto.text = "📎  Change Photo"
        }
    }

    private val viewModel: RequestCredentialsViewModel by viewModels {
        RequestCredentialsViewModelFactory()
    }

    private val appointmentRepository by lazy {
        AppointmentRepositoryImpl(RetrofitClient.apiService)
    }
    // Guard: prevents the nav listener from reacting when we programmatically reset the selection
    private var restoringNavSelection = false
    private var autoRefreshJob: Job? = null
    private var isRefreshingDocumentTypes: Boolean = false
    private var cachedAppointments: List<com.example.registarappointmentsystem.data.model.Appointment> = emptyList()
    private var registrarEmail: String = "registrar@upang.edu.ph"
    private var registrarPhone: String = "(075) 123-4567"
    private var registrarMobile: String = "0917-000-0000"

    override fun onResume() {
        super.onResume()

        if (!enforceAuthOrRedirect()) return
        if (!enforcePasswordPolicyOrRedirect()) return
        if (!::binding.isInitialized) return
        if (!enforceProfileCompleteOrRedirect()) return
        
        restoringNavSelection = true
        binding.bottomNavigationRequest.selectedItemId = R.id.nav_request_credentials
        restoringNavSelection = false
        lifecycleScope.launch { syncNotificationPrefsFromServer() }
        loadRegistrarContactSettings()
        refreshSilently()
        refreshDocumentTypesSilently()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(3_000)
                refreshSilently()
                refreshDocumentTypesSilently()
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
        
        sessionManager = SessionManager(this)
        notificationHelper = NotificationHelper(this)
        lifecycleScope.launch { syncNotificationPrefsFromServer() }
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        binding = ActivityRequestCredentialsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBarBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            // Bottom nav absorbs system gesture bar only; keyboard is handled by adjustPan in manifest
            binding.bottomNavigationRequest.setPadding(0, 0, 0, sysBarBottom)
            insets
        }
        
        setupActivityTracking()
        setupUi()
        setupDocumentListScrollIsolation()
        setupIdPhotoUi()
        setupNavigation()
        observeViewModel()
        fetchDocumentTypes()
        loadRegistrarContactSettings()
        if (!enforceProfileCompleteOrRedirect()) return
    }

    private fun setupDocumentListScrollIsolation() {
        binding.documentTypesScroll.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun setupActivityTracking() {
        binding.root.setOnTouchListener { _, _ ->
            // Keep tracking user interactions for responsiveness
            false
        }
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
    }

    private fun setupIdPhotoUi() {
        binding.buttonPickIdPhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        binding.buttonRemoveIdPhoto.setOnClickListener {
            idPhotoUri = null
            binding.imageIdPhotoPreview.setImageURI(null)
            binding.imageIdPhotoPreview.visibility = View.GONE
            binding.buttonRemoveIdPhoto.visibility = View.GONE
            binding.buttonPickIdPhoto.text = "📎  Choose Photo"
        }
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

    private fun refreshDocumentTypesSilently() {
        if (isRefreshingDocumentTypes) return
        isRefreshingDocumentTypes = true

        lifecycleScope.launch {
            try {
                val latestTypes = appointmentRepository.getDocumentTypes()
                if (latestTypes.isEmpty()) return@launch

                if (!hasDocumentTypeChanges(docTypesList, latestTypes)) return@launch

                docTypesList = latestTypes
                buildCheckboxes(latestTypes, preserveSelection = true)
            } catch (_: Exception) {
                // Silent by design: polling should never interrupt the user flow.
            } finally {
                isRefreshingDocumentTypes = false
            }
        }
    }

    private fun hasDocumentTypeChanges(current: List<DocumentType>, incoming: List<DocumentType>): Boolean {
        if (current.size != incoming.size) return true
        if (current.isEmpty() && incoming.isEmpty()) return false

        val currentSignature = current.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.name}:${it.price}" }
        val incomingSignature = incoming.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.name}:${it.price}" }

        return currentSignature != incomingSignature
    }

    private fun buildCheckboxes(types: List<DocumentType>, preserveSelection: Boolean = false) {
        val container = binding.checkboxContainer
        val previousSelected = if (preserveSelection) selectedDocIds.toSet() else emptySet()
        container.removeAllViews()
        selectedDocIds.clear()
        selectedTotal = 0.0

        val byId = types.associateBy { it.id }
        if (preserveSelection && previousSelected.isNotEmpty()) {
            val kept = previousSelected.filter { byId.containsKey(it) }.toSet()
            selectedDocIds.addAll(kept)
            selectedTotal = kept.sumOf { byId[it]?.priceDouble() ?: 0.0 }
        }

        updatePriceDisplay()

        val estimatedRowHeightPx = 46.dpToPx()
        val maxListHeightPx = 220.dpToPx()
        val targetHeight = (types.size * estimatedRowHeightPx).coerceIn(estimatedRowHeightPx, maxListHeightPx)
        binding.documentTypesScroll.layoutParams = binding.documentTypesScroll.layoutParams.apply {
            height = targetHeight
        }

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
                buttonTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@RequestCredentialsActivity, R.color.status_ready)
                )

                if (selectedDocIds.contains(docType.id)) {
                    isChecked = true
                }

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

        if (isGuest) {
            binding.editStudentIdNumber.filters = arrayOf(
                InputFilter.LengthFilter(MAX_FORMER_STUDENT_ID_LENGTH),
                InputFilter { source, start, end, _, _, _ ->
                    val filtered = buildString {
                        for (index in start until end) {
                            val ch = source[index]
                            if (!FORMER_STUDENT_ID_FORBIDDEN_CHARS.contains(ch)) append(ch)
                        }
                    }
                    if (filtered.length == end - start) null else filtered
                }
            )
        }

        binding.buttonSubmit.setOnClickListener {
            animateButtonPress(binding.buttonSubmit)

            if (!isProfileComplete()) {
                binding.textSubmitStatus.apply {
                    text = "Please complete your profile first (contact number, gender, birthday, address)."
                    setTextColor(resources.getColor(R.color.error_red, null))
                    visibility = View.VISIBLE
                }
                Snackbar.make(binding.root, "Please complete your profile before requesting credentials.", Snackbar.LENGTH_LONG).show()
                startActivity(Intent(this, ProfileActivity::class.java).apply {
                    putExtra("isProfileIncomplete", true)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                return@setOnClickListener
            }

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
            if (isGuest && studentIdNumber.length > MAX_FORMER_STUDENT_ID_LENGTH) {
                binding.textStudentIdError.text = "Former Student ID must be at most $MAX_FORMER_STUDENT_ID_LENGTH characters"
                binding.textStudentIdError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            if (isGuest && FORMER_STUDENT_ID_FORBIDDEN_REGEX.containsMatchIn(studentIdNumber)) {
                binding.textStudentIdError.text = "Former Student ID cannot contain ^ $ # & * ( )"
                binding.textStudentIdError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            binding.textStudentIdError.visibility = View.GONE

            val username = sharedPrefs.getString("user_full_name", "")
                ?.ifBlank { sharedPrefs.getString("user_email", "") ?: "" }
                ?: ""
            val userId = sharedPrefs.getInt("user_id", -1)

            showSubmitConfirmationDialog(
                purpose = purpose,
                reason = reason,
                totalAmount = selectedTotal
            ) {
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
    }

    private fun showSubmitConfirmationDialog(
        purpose: String,
        reason: String,
        totalAmount: Double,
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
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        val title = TextView(this).apply {
            text = "Confirm Application Submission"
            textSize = 18f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Please review your request details before submitting."
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
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        summary.addView(TextView(this).apply {
            text = "Request Summary"
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        summary.addView(TextView(this).apply {
            text = "Document(s): ${purpose.ifBlank { "Not specified" }}"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, dp(6), 0, dp(2))
        })
        summary.addView(TextView(this).apply {
            text = "Purpose: ${reason.ifBlank { "Not specified" }}"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
        })
        summary.addView(TextView(this).apply {
            text = "Total Amount: ₱${"%.2f".format(totalAmount)}"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(summary)

        val remindersCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FFF7ED"))
                setStroke(dp(1), Color.parseColor("#FED7AA"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        remindersCard.addView(TextView(this).apply {
            text = "Quick Reminders"
            textSize = 12f
            setTextColor(Color.parseColor("#9A3412"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        quickReminders.forEach { reminder ->
            remindersCard.addView(TextView(this).apply {
                text = "• $reminder"
                textSize = 12.5f
                setTextColor(Color.parseColor("#7C2D12"))
                setPadding(0, dp(6), 0, 0)
            })
        }
        card.addView(remindersCard)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            layoutParams = lp
        }

        val cancelBtn = Button(this).apply {
            text = "Review Again"
            setTextColor(Color.parseColor("#334155"))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E2E8F0"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        val confirmBtn = Button(this).apply {
            text = "Confirm & Submit"
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        val btnLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btnLp.marginEnd = dp(6)
        buttonRow.addView(cancelBtn, btnLp)
        buttonRow.addView(confirmBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(buttonRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = 0.55f }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun setupNavigation() {
        // Highlight the Request tab as currently active
        binding.bottomNavigationRequest.selectedItemId = R.id.nav_request_credentials

        binding.bottomNavigationRequest.setOnItemSelectedListener { item ->
            if (restoringNavSelection) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    val intent = Intent(this, StudentDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    overridePendingTransition(0, 0)
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
                    overridePendingTransition(0, 0)
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

        // Style notification badge
        binding.textNotifBadge.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.parseColor("#EF4444"))
        }
        // Notification bell: load appointments and show status panel
        binding.buttonNotification.setOnClickListener {
            showNotifications()
        }
        // Help button
        binding.buttonHelp.setOnClickListener {
            showContactDialog()
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
            startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:$email")
            })
        }
        contactsLayout.addView(emailBtn)

        val landlineBtn = createContactButton(
            phone, "Landline", "TEL", Color.parseColor("#E0E7FF"),
            Color.parseColor("#F8FAFC"), Color.parseColor("#C7D2FE")
        ) {
            startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                data = android.net.Uri.parse("tel:${phone.replace(Regex("[^\\d+]"), "")}")
            })
        }
        contactsLayout.addView(landlineBtn)

        val mobileBtn = createContactButton(
            mobile, "Mobile", "SMS", Color.parseColor("#DCFCE7"),
            Color.parseColor("#F8FAFC"), Color.parseColor("#86EFAC")
        ) {
            startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                data = android.net.Uri.parse("tel:${mobile.replace(Regex("[^\\d+]"), "")}")
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
            gravity = android.view.Gravity.CENTER
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
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.CENTER)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
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
                Log.w("RequestCredentials", "Failed to load registrar contact settings", e)
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
            setGravity(android.view.Gravity.CENTER_VERTICAL)
            setBackgroundColor(btnBg)
            setPadding(14, 14, 14, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1E3A8A"))
            gravity = android.view.Gravity.CENTER
        }
        iconContainer.addView(iconTV, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT).apply { gravity = android.view.Gravity.CENTER }
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
            addView(ScrollView(this@RequestCredentialsActivity).apply { addView(container) })
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
                syncNotificationPrefsFromServer()
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

            val dialog = Dialog(this@RequestCredentialsActivity)
            val container = LinearLayout(this@RequestCredentialsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(22), dp(22), dp(16))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.WHITE)
                }
            }

            container.addView(TextView(this@RequestCredentialsActivity).apply {
                text = "Notification Preferences"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            })
            container.addView(TextView(this@RequestCredentialsActivity).apply {
                text = "Control email and in-app appointment updates"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(4), 0, dp(14))
            })

            val statusCard = LinearLayout(this@RequestCredentialsActivity).apply {
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
            val statusSwitch = SwitchCompat(this@RequestCredentialsActivity).apply {
                isChecked = statusInitial
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BFDBFE"))
            }
            statusCard.addView(LinearLayout(this@RequestCredentialsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@RequestCredentialsActivity).apply {
                    text = "Status updates"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(this@RequestCredentialsActivity).apply {
                    text = "Approved, cancelled, processing, payment updates"
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                })
            })
            statusCard.addView(statusSwitch)
            statusCard.setOnClickListener { statusSwitch.isChecked = !statusSwitch.isChecked }
            container.addView(statusCard)

            container.addView(View(this@RequestCredentialsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            })

            val readyCard = LinearLayout(this@RequestCredentialsActivity).apply {
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
            val readySwitch = SwitchCompat(this@RequestCredentialsActivity).apply {
                isChecked = readyInitial
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#BFDBFE"))
            }
            readyCard.addView(LinearLayout(this@RequestCredentialsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@RequestCredentialsActivity).apply {
                    text = "Ready for pickup"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(this@RequestCredentialsActivity).apply {
                    text = "Notify when documents are ready/for claiming"
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                })
            })
            readyCard.addView(readySwitch)
            readyCard.setOnClickListener { readySwitch.isChecked = !readySwitch.isChecked }
            container.addView(readyCard)

            val buttonRow = LinearLayout(this@RequestCredentialsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
            }
            val closeBtn = TextView(this@RequestCredentialsActivity).apply {
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
            val saveBtn = TextView(this@RequestCredentialsActivity).apply {
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
                                Log.e("RequestCredentials", "Notification preference sync failed", e)
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

    /**
     * Detects status and payment changes for appointments and sends appropriate notifications
     * using NotificationHelper methods. Stores previous state in SharedPreferences to detect deltas.
     */
    private fun checkStatusChangesAndNotify(appointments: List<com.example.registarappointmentsystem.data.model.Appointment>) {
        val cache = getSharedPreferences("appointment_status_cache", MODE_PRIVATE)
        val cacheEditor = cache.edit()
        val statusUpdatesEnabled = getSharedPreferences("auth", MODE_PRIVATE).getBoolean("pref_status_updates", true)
        val readyPickupEnabled = getSharedPreferences("auth", MODE_PRIVATE).getBoolean("pref_ready_pickup", true)
        
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
            setOnClickListener {
                dialog.dismiss()
                performLogout()
            }
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
                val appointmentId = result.data
                val photoUri = idPhotoUri

                // Upload ID photo if one was selected
                if (photoUri != null && appointmentId > 0) {
                    binding.textSubmitStatus.apply {
                        text = "Uploading ID photo…"
                        setTextColor(resources.getColor(R.color.text_secondary, null))
                        visibility = View.VISIBLE
                    }
                    binding.progressBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val uploadResult = appointmentRepository.uploadIdPhoto(appointmentId, photoUri, this@RequestCredentialsActivity)
                        binding.progressBar.visibility = View.GONE
                        if (uploadResult.isFailure) {
                            // Non-fatal — request was created; just warn
                            Snackbar.make(binding.root, "Request submitted but ID photo upload failed. You can re-upload later.", Snackbar.LENGTH_LONG).show()
                        }
                        finishAfterSuccess()
                    }
                } else {
                    finishAfterSuccess()
                }
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

    private fun finishAfterSuccess() {
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
            idPhotoUri = null
            binding.imageIdPhotoPreview.setImageURI(null)
            binding.imageIdPhotoPreview.visibility = View.GONE
            binding.buttonRemoveIdPhoto.visibility = View.GONE
            binding.buttonPickIdPhoto.text = "📎  Choose Photo"
            selectedDocIds.clear()
            selectedTotal = 0.0
            buildCheckboxes(docTypesList)
            finish()
        }, 2000)
    }

    private fun animateButtonPress(view: View) {
        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
    }
}