package com.example.registarappointmentsystem.data.model

import com.google.gson.annotations.SerializedName

data class DocumentItem(
    val id: Int = 0,
    @SerializedName("document_type_id") val documentTypeId: Int = 0,
    @SerializedName("document_name") val documentName: String = "",
    val price: String = "0",           // NUMERIC returns as string
    @SerializedName("doc_status") val docStatus: String = "pending"
) {
    fun priceDouble(): Double = price.toDoubleOrNull() ?: 0.0
    fun statusLabel(): String = when (docStatus) {
        "ready"      -> "✓ Ready"
        "processing" -> "⚙ Processing"
        else         -> "⏳ Pending"
    }
}
