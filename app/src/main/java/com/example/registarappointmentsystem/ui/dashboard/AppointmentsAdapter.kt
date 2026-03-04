package com.example.registarappointmentsystem.ui.dashboard

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.Appointment
import java.text.SimpleDateFormat
import java.util.Locale

class AppointmentsAdapter(
    private val onCancelClick: (Appointment) -> Unit,
    private val onSelectDateTimeClick: (Appointment) -> Unit,
    private val onConfirmClick: (Appointment) -> Unit = {},
    private val onPrintStubClick: (Appointment) -> Unit = {}
) : ListAdapter<Appointment, AppointmentsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_appointment_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textRefId: TextView = itemView.findViewById(R.id.textRefId)
        private val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        private val textPurpose: TextView = itemView.findViewById(R.id.textPurpose)
        private val textDate: TextView = itemView.findViewById(R.id.textDate)
        private val textNote: TextView = itemView.findViewById(R.id.textNote)
        private val buttonCancel: Button = itemView.findViewById(R.id.buttonCancel)
        private val buttonSelectDate: Button = itemView.findViewById(R.id.buttonSelectDate)
        private val buttonConfirm: Button = itemView.findViewById(R.id.buttonConfirm)
        private val buttonPrintStub: Button = itemView.findViewById(R.id.buttonPrintStub)
        private val viewStatusStrip: View = itemView.findViewById(R.id.viewStatusStrip)
        private val statusBadgeLayout: LinearLayout = itemView.findViewById(R.id.statusBadgeLayout)

        // Step tracker circles, lines, labels
        private val stepCircle1: View = itemView.findViewById(R.id.stepCircle1)
        private val stepCircle2: View = itemView.findViewById(R.id.stepCircle2)
        private val stepCircle3: View = itemView.findViewById(R.id.stepCircle3)
        private val stepCircle4: View = itemView.findViewById(R.id.stepCircle4)
        private val stepLine12: View = itemView.findViewById(R.id.stepLine12)
        private val stepLine23: View = itemView.findViewById(R.id.stepLine23)
        private val stepLine34: View = itemView.findViewById(R.id.stepLine34)
        private val stepLabel1: TextView = itemView.findViewById(R.id.stepLabel1)
        private val stepLabel2: TextView = itemView.findViewById(R.id.stepLabel2)
        private val stepLabel3: TextView = itemView.findViewById(R.id.stepLabel3)
        private val stepLabel4: TextView = itemView.findViewById(R.id.stepLabel4)

        // Colors per step: Submitted(amber), Under Review(blue), Ready(green), Completed(purple)
        private val stepColors = intArrayOf(
            Color.parseColor("#F59E0B"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#10B981"),
            Color.parseColor("#8B5CF6")
        )
        private val colorInactive = Color.parseColor("#BDBDBD")

        fun bind(appointment: Appointment) {
            textRefId.text = "Ref #${appointment.id}"

            // Set default click listeners and button text at the top.
            // Branch-specific overrides (e.g. "Submit Payment") will replace these below.
            buttonCancel.text = "Cancel Request"
            buttonCancel.backgroundTintList =
                ContextCompat.getColorStateList(itemView.context, R.color.error_red)
            buttonCancel.setOnClickListener { onCancelClick(appointment) }
            buttonSelectDate.setOnClickListener { onSelectDateTimeClick(appointment) }
            buttonConfirm.setOnClickListener { onConfirmClick(appointment) }
            buttonPrintStub.setOnClickListener { onPrintStubClick(appointment) }

            // Purpose — show per-document statuses when available
            val docItems = appointment.documentItems
            if (!docItems.isNullOrEmpty()) {
                textPurpose.text = docItems.joinToString("\n") { doc ->
                    val statusLabel = when (doc.docStatus.lowercase()) {
                        "ready"      -> "Ready"
                        "processing" -> "Processing"
                        else         -> "Pending"
                    }
                    "\u2022 ${doc.documentName} — $statusLabel"
                }
            } else {
                textPurpose.text = appointment.purpose ?: "—"
            }

            // Date line — pending shows a friendly waiting message
            val statusLower = appointment.status?.lowercase()?.trim()
            val displayDate = when {
                statusLower == "incomplete" || statusLower == "rejected" ||
                statusLower == "no_show" || statusLower == "cancelled" -> null
                statusLower == "pending" ->
                    "⏳ Waiting for Registrar's Approval"
                !appointment.student_pickup_date.isNullOrEmpty() ->
                    "Pickup: ${formatDate(appointment.student_pickup_date!!)}" +
                        if (!appointment.pickup_time.isNullOrEmpty()) " at ${appointment.pickup_time}" else ""
                !appointment.ready_date.isNullOrEmpty() ->
                    "Available from: ${formatDate(appointment.ready_date!!)}"
                !appointment.date.isNullOrEmpty() -> "Scheduled: ${formatDate(appointment.date!!)}"
                else -> "Date not set"
            }
            if (displayDate != null) {
                textDate.visibility = View.VISIBLE
                textDate.text = displayDate
            } else {
                textDate.visibility = View.GONE
            }

            // Admin note — reset styling first (ViewHolder gets reused by RecyclerView)
            val note = appointment.admin_comment
            textNote.setBackgroundColor(Color.TRANSPARENT)
            textNote.setTextColor(Color.parseColor("#555555"))
            textNote.setPadding(0, 0, 0, 0)
            if (note.isNullOrEmpty()) {
                textNote.visibility = View.GONE
            } else {
                textNote.visibility = View.VISIBLE
                textNote.text = "📋 $note"
            }

            val hasSub = !appointment.student_pickup_date.isNullOrEmpty()

            // Status badge + step tracker + button visibility
            when (statusLower) {
                "approved" -> {
                    textStatus.text = "APPROVED"
                    val color = ContextCompat.getColor(itemView.context, R.color.status_approved)
                    applyStatusColor(color)
                    updateStepTracker(2)
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.VISIBLE

                    val payAmt  = appointment.paymentAmount?.toDoubleOrNull()
                    val paySt   = appointment.paymentStatus

                    if (payAmt != null && paySt != "verified") {
                        // Payment required — show payment info instead of date picker button
                        buttonSelectDate.visibility = View.GONE
                        val amtStr = String.format("\u20b1%.2f", payAmt)
                        val payInfo = when (paySt) {
                            "submitted" ->
                                "🕐 Payment reference submitted!\nAmount: $amtStr\nRef: ${appointment.paymentReference}\nWaiting for registrar to verify."
                            "rejected" ->
                                "⚠️ Payment reference rejected.\nAmount: $amtStr\n\nPlease tap \"Submit Payment\" to re-submit a correct reference."
                            else ->
                                "💳 Payment Required: $amtStr\n\nPay via GCash or bank transfer, then tap \"Submit Payment\" to send your reference number."
                        }
                        textNote.visibility = View.VISIBLE
                        textNote.setBackgroundColor(if (paySt == "submitted") Color.parseColor("#E0F2FE") else Color.parseColor("#FEF9C3"))
                        textNote.setTextColor(if (paySt == "submitted") Color.parseColor("#0C4A6E") else Color.parseColor("#854D0E"))
                        textNote.setPadding(20, 12, 20, 12)
                        textNote.text = payInfo
                        // Re-purpose buttonCancel into a "Submit Payment" button only when not yet submitted
                        if (paySt != "submitted") {
                            buttonCancel.text = "💳 Submit Payment"
                            buttonCancel.backgroundTintList =
                                ContextCompat.getColorStateList(itemView.context, R.color.status_approved)
                            buttonCancel.setOnClickListener { onSelectDateTimeClick(appointment) }
                        } else {
                            buttonCancel.visibility = View.GONE
                        }
                    } else {
                        // No payment required, or payment verified
                        buttonSelectDate.visibility = View.VISIBLE
                        buttonCancel.visibility = View.VISIBLE
                        buttonCancel.text = "Cancel Request"
                        val graceText = "⏰ Please arrive 5 minutes early. Students arriving more than 5 minutes late may be marked as No-Show."
                        if (paySt == "verified") {
                            textNote.visibility = View.VISIBLE
                            textNote.setBackgroundColor(Color.parseColor("#F0FDF4"))
                            textNote.setTextColor(Color.parseColor("#166534"))
                            textNote.setPadding(20, 12, 20, 12)
                            textNote.text = "✓ Payment verified! Please select your pickup schedule.\n\n$graceText"
                        } else {
                            textNote.visibility = View.VISIBLE
                            textNote.setBackgroundColor(Color.parseColor("#FEF3C7"))
                            textNote.setTextColor(Color.parseColor("#B45309"))
                            textNote.setPadding(20, 12, 20, 12)
                            textNote.text = if (!note.isNullOrEmpty()) "\uD83D\uDCCB $note\n\n$graceText" else graceText
                        }
                    }
                }
                "ready" -> {
                    textStatus.text = "READY FOR PICKUP"
                    val color = ContextCompat.getColor(itemView.context, R.color.status_ready)
                    applyStatusColor(color)
                    updateStepTracker(3)
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = if (hasSub) View.VISIBLE else View.GONE
                    buttonCancel.visibility = View.GONE
                    // Grace period reminder — shown under scheduled pickup info
                    if (hasSub) {
                        val graceText = "⏰ Please arrive 5 minutes early. Students arriving more than 5 minutes late may be marked as No-Show."
                        textNote.visibility = View.VISIBLE
                        textNote.setBackgroundColor(Color.parseColor("#FEF3C7"))
                        textNote.setTextColor(Color.parseColor("#B45309"))
                        textNote.setPadding(20, 12, 20, 12)
                        textNote.text = if (!note.isNullOrEmpty()) "📋 $note\n\n$graceText" else graceText
                    }
                }
                "pending" -> {
                    textStatus.text = "PENDING"
                    val color = ContextCompat.getColor(itemView.context, R.color.status_pending)
                    applyStatusColor(color)
                    updateStepTracker(1)
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.VISIBLE
                }
                "completed" -> {
                    textStatus.text = "COMPLETED"
                    val color = ContextCompat.getColor(itemView.context, R.color.status_completed)
                    applyStatusColor(color)
                    updateStepTracker(5) // 5 > 4 means all steps done
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.GONE
                }
                "incomplete" -> {
                    textStatus.text = "INCOMPLETE"
                    val color = Color.parseColor("#B45309")
                    applyStatusColor(color)
                    updateStepTracker(0)
                    textDate.visibility = View.GONE
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.GONE
                    // Show missing requirements note in amber box
                    val note = appointment.admin_comment
                    textNote.visibility = View.VISIBLE
                    textNote.setTextColor(Color.parseColor("#92400E"))
                    textNote.setBackgroundColor(Color.parseColor("#FEF3C7"))
                    (textNote.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                        it.setMargins(0, 12, 0, 0)
                    }
                    textNote.setPadding(20, 12, 20, 12)
                    textNote.text = if (!note.isNullOrEmpty()) {
                        "⚠️ Missing Requirements:\n$note\n\nYour appointment is still open — no need to rebook."
                    } else {
                        "⚠️ Please submit the missing requirements to the registrar's office.\n\nYour appointment is still open — no need to rebook."
                    }
                }
                "rejected" -> {
                    textStatus.text = "REJECTED"
                    val color = ContextCompat.getColor(itemView.context, R.color.status_rejected)
                    applyStatusColor(color)
                    updateStepTracker(0) // 0 = all inactive (rejected)
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.GONE
                }
                "no_show" -> {
                    textStatus.text = "NO-SHOW"
                    val color = ContextCompat.getColor(itemView.context, R.color.status_rejected)
                    applyStatusColor(color)
                    updateStepTracker(0)
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.GONE
                }
                "cancelled" -> {
                    textStatus.text = "CANCELLED"
                    val color = Color.parseColor("#757575")
                    applyStatusColor(color)
                    updateStepTracker(0)
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.GONE
                    textNote.visibility = View.VISIBLE
                    textNote.setBackgroundColor(Color.parseColor("#F5F5F5"))
                    textNote.setTextColor(Color.parseColor("#616161"))
                    textNote.setPadding(20, 12, 20, 12)
                    val cancelNote = appointment.admin_comment
                    textNote.text = if (!cancelNote.isNullOrBlank()) {
                        "❌ $cancelNote"
                    } else {
                        "This request was cancelled."
                    }
                }
                "for_claiming" -> {
                    textStatus.text = "FOR CLAIMING"
                    val color = Color.parseColor("#E65100")
                    applyStatusColor(color)
                    updateStepTracker(0)
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.GONE
                    textNote.visibility = View.VISIBLE
                    textNote.setBackgroundColor(Color.parseColor("#FFF3E0"))
                    textNote.setTextColor(Color.parseColor("#BF360C"))
                    textNote.setPadding(20, 12, 20, 12)
                    textNote.text = "\uD83C\uDFEB Your documents are ready at the Registrar's Office.\n\nPlease visit during office hours (Mon–Fri, 8AM–5PM) and bring a valid ID to claim your documents."
                }
                else -> {
                    textStatus.text = if (appointment.status.isNullOrEmpty()) "PENDING"
                                      else appointment.status.uppercase()
                    val color = ContextCompat.getColor(itemView.context, R.color.status_pending)
                    applyStatusColor(color)
                    updateStepTracker(1)
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonPrintStub.visibility = View.GONE
                    buttonCancel.visibility = View.GONE
                }
            }

            // Click listeners – defaults already set at top of bind().
            // Branch-specific overrides (Submit Payment) were applied above.
        }

        /** Apply status color to the strip, badge background, and status text. */
        private fun applyStatusColor(color: Int) {
            viewStatusStrip.setBackgroundColor(color)
            statusBadgeLayout.setBackgroundColor(color)
            textStatus.setTextColor(Color.WHITE)
        }

        /**
         * Drive the step tracker visuals.
         * @param activeStep 1=Submitted active, 2=Under Review active, 3=Ready active,
         *                   5= all done (completed).  0 = all inactive (rejected/no_show).
         */
        private fun updateStepTracker(activeStep: Int) {
            val density = itemView.context.resources.displayMetrics.density
            val strokePx = (2.5f * density).toInt()

            val circles = arrayOf(stepCircle1, stepCircle2, stepCircle3, stepCircle4)
            val labels = arrayOf(stepLabel1, stepLabel2, stepLabel3, stepLabel4)
            val lines = arrayOf(stepLine12, stepLine23, stepLine34)

            for (i in 0..3) {
                val stepNum = i + 1
                val color = stepColors[i]
                when {
                    activeStep == 0 -> {
                        circles[i].background = makeCircle(filled = false, strokePx, colorInactive)
                        stylizeLabel(labels[i], done = false, active = false)
                    }
                    stepNum < activeStep || activeStep > 4 -> {
                        // Completed step
                        circles[i].background = makeCircle(filled = true, strokePx, color)
                        stylizeLabel(labels[i], done = true, active = false)
                    }
                    stepNum == activeStep -> {
                        // Active / current step
                        circles[i].background = makeCircle(filled = false, strokePx, color)
                        stylizeLabel(labels[i], done = false, active = true)
                    }
                    else -> {
                        // Future step
                        circles[i].background = makeCircle(filled = false, strokePx, colorInactive)
                        stylizeLabel(labels[i], done = false, active = false)
                    }
                }
            }

            // Color the connecting lines: line[i] between circles[i] and circles[i+1]
            // Line is fully colored only when both endpoints are "done"
            for (i in 0..2) {
                val lineIsDone = activeStep > i + 2 || activeStep > 4
                lines[i].setBackgroundColor(if (lineIsDone) stepColors[i] else colorInactive)
            }
        }

        private fun makeCircle(filled: Boolean, strokePx: Int, color: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (filled) {
                    setColor(color)
                    setStroke(0, Color.TRANSPARENT)
                } else {
                    setColor(Color.WHITE)
                    setStroke(strokePx, color)
                }
            }
        }

        private fun stylizeLabel(label: TextView, done: Boolean, active: Boolean) {
            when {
                done   -> { label.setTextColor(Color.parseColor("#555555")); label.typeface = Typeface.DEFAULT }
                active -> { label.setTextColor(Color.parseColor("#1A1A1A")); label.typeface = Typeface.DEFAULT_BOLD }
                else   -> { label.setTextColor(Color.parseColor("#AAAAAA")); label.typeface = Typeface.DEFAULT }
            }
        }
    }

    /** Converts an ISO date string (yyyy-MM-dd or full ISO) to "Mar 3, 2026" */
    private fun formatDate(raw: String): String {
        return try {
            // Dates from the API may be full UTC ISO timestamps (e.g. "2026-03-05T16:00:00.000Z")
            // which represent midnight in UTC+8. We must parse in UTC so the local-timezone
            // display lands on the correct calendar day.
            val date = if (raw.contains('T')) {
                // Full ISO timestamp — parse in UTC, display in device local timezone
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(raw.substring(0, 19))
            } else {
                // Plain date string "yyyy-MM-dd" — parse in UTC to avoid day shift
                SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(raw.substring(0, 10))
            } ?: return raw
            // Format in the device's local timezone so the day shown matches what the
            // registrar and student selected on the web
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
        } catch (e: Exception) {
            raw
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Appointment>() {
        override fun areItemsTheSame(oldItem: Appointment, newItem: Appointment): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Appointment, newItem: Appointment): Boolean {
            return oldItem == newItem
        }
    }
}
