package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.ProductAnalysisResult
import com.example.data.db.AppDatabase
import com.example.data.model.Invoice
import com.example.data.model.InvoiceItem
import com.example.data.model.Product
import com.example.data.repository.ShopRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(FlowPreview::class)
class ShopViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = ShopRepository(database.productDao(), database.invoiceDao())

    // UI state streams
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // Reactive products list filtered by query and category
    val products: StateFlow<List<Product>> = combine(
        repository.allProducts,
        _searchQuery,
        _selectedCategory
    ) { allProds, query, category ->
        var filtered = allProds
        if (category != "All") {
            filtered = filtered.filter { it.category.equals(category, ignoreCase = true) }
        }
        if (query.isNotEmpty()) {
            filtered = filtered.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.sku.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
            }
        }
        filtered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All billing invoices
    val invoices: StateFlow<List<Invoice>> = repository.allInvoices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Billing Cart
    private val _cartItems = MutableStateFlow<List<InvoiceItem>>(emptyList())
    val cartItems: StateFlow<List<InvoiceItem>> = _cartItems.asStateFlow()

    // Bill Header Info
    val customerName = MutableStateFlow("")
    val customerPhone = MutableStateFlow("")
    val selectedPaymentMethod = MutableStateFlow("UPI") // "Cash", "UPI", "Card"

    // AI Visual Scan states
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisResult = MutableStateFlow<ProductAnalysisResult?>(null)
    val analysisResult: StateFlow<ProductAnalysisResult?> = _analysisResult.asStateFlow()

    private val _matchedLocalProduct = MutableStateFlow<Product?>(null)
    val matchedLocalProduct: StateFlow<Product?> = _matchedLocalProduct.asStateFlow()

    private val _scannedBitmap = MutableStateFlow<Bitmap?>(null)
    val scannedBitmap: StateFlow<Bitmap?> = _scannedBitmap.asStateFlow()

    init {
        // Seed database if empty
        viewModelScope.launch {
            repository.allProducts.first().let { currentList ->
                if (currentList.isEmpty()) {
                    seedInitialProducts()
                }
            }
        }
    }

    // Filter categories
    val categories = listOf("All", "Electrical", "Plumbing", "Tools", "Fasteners", "Safety", "Paint", "General")

    // --- Product CRUD Actions ---
    fun saveProduct(product: Product) {
        viewModelScope.launch {
            if (product.id == 0) {
                repository.insertProduct(product)
            } else {
                repository.updateProduct(product)
            }
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            repository.deleteProduct(product)
        }
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    // --- Active Cart & Invoicing Actions ---
    fun addToCart(product: Product, quantity: Int = 1) {
        val current = _cartItems.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.productId == product.id }
        if (existingIndex >= 0) {
            val oldItem = current[existingIndex]
            val newQty = oldItem.quantity + quantity
            if (newQty <= product.stock) {
                current[existingIndex] = oldItem.copy(quantity = newQty)
            }
        } else {
            if (quantity <= product.stock) {
                current.add(
                    InvoiceItem(
                        productId = product.id,
                        productName = product.name,
                        quantity = quantity,
                        price = product.salePrice,
                        unit = product.unit
                    )
                )
            }
        }
        _cartItems.value = current
    }

    fun updateCartQuantity(item: InvoiceItem, newQty: Int) {
        if (newQty <= 0) {
            removeCartItem(item)
            return
        }
        viewModelScope.launch {
            val product = repository.getProductById(item.productId)
            if (product != null && newQty <= product.stock) {
                val current = _cartItems.value.toMutableList()
                val index = current.indexOfFirst { it.productId == item.productId }
                if (index >= 0) {
                    current[index] = current[index].copy(quantity = newQty)
                    _cartItems.value = current
                }
            }
        }
    }

    fun removeCartItem(item: InvoiceItem) {
        val current = _cartItems.value.toMutableList()
        current.removeAll { it.productId == item.productId }
        _cartItems.value = current
    }

    fun clearCart() {
        _cartItems.value = emptyList()
        customerName.value = ""
        customerPhone.value = ""
    }

    fun checkout(onSuccess: (Invoice) -> Unit, onError: (String) -> Unit) {
        val items = _cartItems.value
        if (items.isEmpty()) {
            onError("Cart is empty!")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subtotal = items.sumOf { it.price * it.quantity }
                val taxRate = 18.0 // 18% GST standard
                val taxAmount = subtotal * (taxRate / 100.0)
                val grandTotal = subtotal + taxAmount

                // Create clean unique invoice number
                val dateStr = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
                val invoiceNum = "INV-$dateStr"

                val invoice = Invoice(
                    invoiceNumber = invoiceNum,
                    customerName = customerName.value.ifEmpty { "Cash Customer" },
                    customerPhone = customerPhone.value.ifEmpty { "N/A" },
                    timestamp = System.currentTimeMillis(),
                    items = items,
                    subtotal = subtotal,
                    taxRate = taxRate,
                    taxAmount = taxAmount,
                    grandTotal = grandTotal,
                    paymentMethod = selectedPaymentMethod.value
                )

                // Save to DB
                repository.insertInvoice(invoice)

                // Deduct inventory stock
                for (item in items) {
                    val prod = repository.getProductById(item.productId)
                    if (prod != null) {
                        val newStock = (prod.stock - item.quantity).coerceAtLeast(0)
                        repository.updateProduct(prod.copy(stock = newStock))
                    }
                }

                // Clear states
                withContext(Dispatchers.Main) {
                    clearCart()
                    onSuccess(invoice)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Failed to save invoice")
                }
            }
        }
    }

    // --- AI Camera Vision Lookup Actions ---
    fun analyzePhoto(bitmap: Bitmap) {
        _scannedBitmap.value = bitmap
        _isAnalyzing.value = true
        _analysisResult.value = null
        _matchedLocalProduct.value = null

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Convert bitmap to base64
            val base64 = bitmapToBase64(bitmap)
            // 2. Call Gemini API
            val result = repository.analyzeProductPhoto(base64)
            
            withContext(Dispatchers.Main) {
                _isAnalyzing.value = false
                _analysisResult.value = result

                if (result != null) {
                    // Try to match scanned item name in our local database
                    matchLocalProduct(result.productName)
                }
            }
        }
    }

    fun clearScan() {
        _scannedBitmap.value = null
        _analysisResult.value = null
        _matchedLocalProduct.value = null
        _isAnalyzing.value = false
    }

    private suspend fun matchLocalProduct(scannedName: String) {
        val allLocal = repository.allProducts.first()
        // Simple fuzzy match or check if name contains a word
        val words = scannedName.lowercase().split(" ", "-", "_").filter { it.length > 2 }
        val matched = allLocal.firstOrNull { localProduct ->
            val localLower = localProduct.name.lowercase()
            localLower.contains(scannedName.lowercase()) ||
            words.any { localLower.contains(it) }
        }
        _matchedLocalProduct.value = matched
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Compress bitmap slightly to reduce payload size to Gemini (which is faster and uses less bandwidth)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    // --- DB Seed data ---
    private suspend fun seedInitialProducts() {
        val initialList = listOf(
            Product(
                name = "Havells 1.5 sq mm Wire Red (90m)",
                sku = "EL-WIR-001",
                category = "Electrical",
                purchasePrice = 1100.0,
                salePrice = 1450.0,
                stock = 15,
                unit = "pcs",
                location = "Rack A1",
                description = "High quality copper conductor with fire retardant insulation."
            ),
            Product(
                name = "Polycab 2.5 sq mm Wire Blue (90m)",
                sku = "EL-WIR-002",
                category = "Electrical",
                purchasePrice = 1750.0,
                salePrice = 2200.0,
                stock = 8,
                unit = "pcs",
                location = "Rack A2",
                description = "Flame retardant low smoke (FRLS) single core copper cable."
            ),
            Product(
                name = "Syska 9W LED Bulb White",
                sku = "EL-BUL-009",
                category = "Electrical",
                purchasePrice = 75.0,
                salePrice = 120.0,
                stock = 45,
                unit = "pcs",
                location = "Counter Shell",
                description = "Cool day light, energy saving B22 LED lamp."
            ),
            Product(
                name = "Anchor Roma 6A One-Way Switch",
                sku = "EL-SWI-006",
                category = "Electrical",
                purchasePrice = 22.0,
                salePrice = 35.0,
                stock = 120,
                unit = "pcs",
                location = "Box B4",
                description = "Elegant modular switches with a smooth glossy texture."
            ),
            Product(
                name = "PVC Conduit Pipe 25mm (10ft)",
                sku = "PL-PIP-025",
                category = "Plumbing",
                purchasePrice = 50.0,
                salePrice = 80.0,
                stock = 60,
                unit = "pcs",
                location = "Side Yard",
                description = "Heavy duty rigid PVC conduit for internal or external wiring layouts."
            ),
            Product(
                name = "Taparia Combination Pliers 8 inch",
                sku = "TL-PLI-008",
                category = "Tools",
                purchasePrice = 280.0,
                salePrice = 380.0,
                stock = 14,
                unit = "pcs",
                location = "Tools Board",
                description = "Sturdy high carbon steel insulated pliers for wire cutting and gripping."
            ),
            Product(
                name = "Stainless Steel Screws 1.5 in",
                sku = "FN-SCR-150",
                category = "Fasteners",
                purchasePrice = 120.0,
                salePrice = 180.0,
                stock = 30,
                unit = "box",
                location = "Drawer D12",
                description = "Self-tapping robust wood/wall screws, pack of 100."
            ),
            Product(
                name = "Supreme 1-inch Brass Ball Valve",
                sku = "PL-VAL-001",
                category = "Plumbing",
                purchasePrice = 350.0,
                salePrice = 490.0,
                stock = 12,
                unit = "pcs",
                location = "Rack C2",
                description = "Threaded brass valve for leak-proof fluid pressure flow."
            )
        )

        for (prod in initialList) {
            repository.insertProduct(prod)
        }
    }
}
