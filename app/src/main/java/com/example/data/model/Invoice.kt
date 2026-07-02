package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class InvoiceItem(
    val productId: Int,
    val productName: String,
    val quantity: Int,
    val price: Double,
    val unit: String
)

@Entity(tableName = "invoices")
@JsonClass(generateAdapter = true)
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val invoiceNumber: String,
    val customerName: String,
    val customerPhone: String,
    val timestamp: Long,
    val items: List<InvoiceItem>,
    val subtotal: Double,
    val taxRate: Double = 18.0, // Standard 18% GST for hardware/electrical in India
    val taxAmount: Double,
    val discountAmount: Double = 0.0,
    val grandTotal: Double,
    val paymentMethod: String // "Cash", "UPI", "Card"
)
