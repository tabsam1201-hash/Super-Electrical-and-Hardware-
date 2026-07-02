package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@Entity(tableName = "products")
@JsonClass(generateAdapter = true)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val sku: String,
    val category: String,
    val purchasePrice: Double,
    val salePrice: Double,
    val stock: Int,
    val unit: String, // e.g. "pcs", "box", "meters"
    val location: String = "", // e.g. "Rack A", "Shelf 3"
    val description: String = "",
    val imageUri: String? = null
)
