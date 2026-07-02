package com.example.data.repository

import com.example.BuildConfig
import com.example.data.api.*
import com.example.data.db.InvoiceDao
import com.example.data.db.ProductDao
import com.example.data.model.Invoice
import com.example.data.model.Product
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ShopRepository(
    private val productDao: ProductDao,
    private val invoiceDao: InvoiceDao,
    private val geminiService: GeminiApiService = RetrofitClient.geminiService
) {
    val allProducts: Flow<List<Product>> = productDao.getAllProducts()
    val allInvoices: Flow<List<Invoice>> = invoiceDao.getAllInvoices()

    fun searchProducts(query: String): Flow<List<Product>> {
        return productDao.searchProducts("%$query%")
    }

    fun getProductsByCategory(category: String): Flow<List<Product>> {
        return productDao.getProductsByCategory(category)
    }

    suspend fun getProductById(id: Int): Product? = withContext(Dispatchers.IO) {
        productDao.getProductById(id)
    }

    suspend fun insertProduct(product: Product): Long = withContext(Dispatchers.IO) {
        productDao.insertProduct(product)
    }

    suspend fun updateProduct(product: Product) = withContext(Dispatchers.IO) {
        productDao.updateProduct(product)
    }

    suspend fun deleteProduct(product: Product) = withContext(Dispatchers.IO) {
        productDao.deleteProduct(product)
    }

    suspend fun insertInvoice(invoice: Invoice): Long = withContext(Dispatchers.IO) {
        invoiceDao.insertInvoice(invoice)
    }

    suspend fun getInvoiceById(id: Int): Invoice? = withContext(Dispatchers.IO) {
        invoiceDao.getInvoiceById(id)
    }

    suspend fun deleteInvoice(invoice: Invoice) = withContext(Dispatchers.IO) {
        invoiceDao.deleteInvoice(invoice)
    }

    /**
     * Sends the captured base64 image to Gemini and parses the structured response.
     */
    suspend fun analyzeProductPhoto(base64Image: String): ProductAnalysisResult? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext ProductAnalysisResult(
                productName = "Sample MCB Switch (Demo Mode)",
                category = "Electrical",
                estimatedPrice = 280.00,
                confidence = "Medium",
                description = "Configure your actual Gemini API Key in AI Studio secrets to use active AI vision search. Currently showing demo values.",
                specifications = "Rating: 16A Single Pole, Type: C-Curve"
            )
        }

        val prompt = """
            You are an expert hardware and electrical shop manager. Analyze this photo. It displays an item commonly found in an electrical or hardware retail shop.
            Identify what it is. Provide its productName, its category (one of: "Electrical", "Plumbing", "Tools", "Fasteners", "Safety", "Paint", "General"), and estimate its reasonable standard Indian market retail price in Indian Rupees (INR) (double numeric value like 150.0). Provide a clear description and confidence level ("High", "Medium", "Low") based on visibility.
            
            Return ONLY a valid JSON object matching this structure. Do NOT wrap the JSON in markdown code blocks (e.g. no ```json). Only return the raw JSON text:
            {
              "productName": "Item Name",
              "category": "Electrical",
              "estimatedPrice": 150.00,
              "confidence": "High",
              "description": "Short explanation of the item.",
              "specifications": "Voltage/amps rating, size, etc."
            }
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.2f
            )
        )

        try {
            val response = geminiService.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val adapter = moshi.adapter(ProductAnalysisResult::class.java)
                adapter.fromJson(jsonText)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
