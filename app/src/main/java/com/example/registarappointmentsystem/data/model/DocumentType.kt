package com.example.registarappointmentsystem.data.model

import com.google.gson.annotations.SerializedName

data class DocumentType(
    val id: Int,
    val name: String,
    val price: String  // comes as "150.00" from PostgreSQL NUMERIC
) {
    fun priceDouble(): Double = price.toDoubleOrNull() ?: 0.0
}
