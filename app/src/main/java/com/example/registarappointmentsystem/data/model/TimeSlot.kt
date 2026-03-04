package com.example.registarappointmentsystem.data.model

import com.google.gson.annotations.SerializedName

data class TimeSlot(
    @SerializedName("time") val time: String,
    @SerializedName("available") val available: Boolean
)

data class TimeSlotsResponse(
    @SerializedName("slots") val slots: List<TimeSlot>
)
