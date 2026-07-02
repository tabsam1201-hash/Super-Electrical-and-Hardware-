package com.example.data.db

import androidx.room.TypeConverter
import com.example.data.model.InvoiceItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class Converters {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    
    private val invoiceItemListType = Types.newParameterizedType(List::class.java, InvoiceItem::class.java)
    private val adapter = moshi.adapter<List<InvoiceItem>>(invoiceItemListType)

    @TypeConverter
    fun fromInvoiceItemList(value: List<InvoiceItem>?): String {
        return value?.let { adapter.toJson(it) } ?: "[]"
    }

    @TypeConverter
    fun toInvoiceItemList(value: String?): List<InvoiceItem> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            adapter.fromJson(value) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
