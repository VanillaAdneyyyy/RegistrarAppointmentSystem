package com.example.registarappointmentsystem.data.remote.response

import com.example.registarappointmentsystem.data.model.User
import com.google.gson.annotations.SerializedName

data class UsersResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("data")
    val users: List<User> // Or just val user: User if it returns a single object
)