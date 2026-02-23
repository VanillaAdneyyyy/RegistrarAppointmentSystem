package com.example.registarappointmentsystem.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.data.model.Appointment

class AppointmentsAdapter(

    private val onCancelClick: (Appointment) -> Unit,
    private val onSelectDateTimeClick: (Appointment) -> Unit
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

        fun bind(appointment: Appointment) {
            textRefId.text = "Ref ID: ${appointment.id}"
            textPurpose.text = appointment.purpose
            textDate.text = "Date: ${appointment.date ?: "Not scheduled"}"
            
            // Status styling
            when (appointment.status?.lowercase()) {
                "approved" -> {
                    textStatus.text = "APPROVED"
                    textStatus.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.status_approved))
                    buttonSelectDate.visibility = View.VISIBLE
                    buttonConfirm.visibility = View.VISIBLE
                    buttonCancel.visibility = View.GONE
                }
                "pending" -> {
                    textStatus.text = "PENDING"
                    textStatus.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.status_pending))
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonCancel.visibility = View.VISIBLE
                }
                "ready" -> {
                    textStatus.text = "READY"
                    textStatus.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.status_ready))
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.VISIBLE
                    buttonCancel.visibility = View.GONE
                }
                else -> {
                    textStatus.text = appointment.status?.uppercase() ?: "UNKNOWN"
                    textStatus.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.status_pending))
                    buttonSelectDate.visibility = View.GONE
                    buttonConfirm.visibility = View.GONE
                    buttonCancel.visibility = View.VISIBLE
                }
            }

            textNote.text = appointment.admin_comment ?: "No notes from registrar"

            // Click handlers
            buttonCancel.setOnClickListener { onCancelClick(appointment) }
            buttonSelectDate.setOnClickListener { onSelectDateTimeClick(appointment) }
            buttonConfirm.setOnClickListener { 
                // TODO: Confirm appointment
            }
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
