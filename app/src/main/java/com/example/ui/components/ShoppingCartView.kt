package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.ShoppingItem
import com.example.data.ShoppingList
import com.example.ui.AppViewModel
import com.example.util.AmazonLinkParser
import java.text.NumberFormat
import java.util.Locale

// Theme colors for Shopping Cart
private val DarkBg = Color(0xFF0C0D14)
private val CardBg = Color(0xFF151622)
private val CardBorder = Color(0xFF24263A)
private val CyanAccent = Color(0xFF00E5FF)
private val AmazonOrange = Color(0xFFFF9900)
private val GreenAccent = Color(0xFF00E676)
private val TextMuted = Color(0xFF8F93A7)

enum class ShoppingTabFilter { ALL, TO_BUY, IN_CART }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingCartView(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shoppingLists by viewModel.shoppingLists.collectAsStateWithLifecycle()
    val selectedListId by viewModel.selectedShoppingListId.collectAsStateWithLifecycle()
    val items by viewModel.currentShoppingItems.collectAsStateWithLifecycle()
    val isParsingAmazon by viewModel.isParsingAmazonLink.collectAsStateWithLifecycle()
    val parsedProduct by viewModel.parsedAmazonProduct.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.ensureDefaultShoppingListExists()
    }

    // Dialog & UI states
    var showCreateListDialog by remember { mutableStateOf(false) }
    var showEditListDialog by remember { mutableStateOf(false) }
    var listToEdit by remember { mutableStateOf<ShoppingList?>(null) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var showAmazonLinkDialog by remember { mutableStateOf(false) }
    var showEditItemDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<ShoppingItem?>(null) }

    var currentFilter by remember { mutableStateOf(ShoppingTabFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    // Active list computation
    val activeList = remember(shoppingLists, selectedListId) {
        shoppingLists.firstOrNull { it.id == selectedListId } ?: shoppingLists.firstOrNull()
    }

    // Filter items
    val filteredItems = remember(items, currentFilter, searchQuery) {
        items.filter { item ->
            val matchesFilter = when (currentFilter) {
                ShoppingTabFilter.ALL -> true
                ShoppingTabFilter.TO_BUY -> !item.isPurchased
                ShoppingTabFilter.IN_CART -> item.isPurchased
            }
            val matchesSearch = searchQuery.isBlank() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.category.contains(searchQuery, ignoreCase = true) ||
                    item.notes.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    // Calculation of Totals
    val totalCost = remember(items) { items.sumOf { it.totalCost } }
    val purchasedCost = remember(items) { items.filter { it.isPurchased }.sumOf { it.totalCost } }
    val remainingCost = remember(items) { items.filter { !it.isPurchased }.sumOf { it.totalCost } }
    val totalUnits = remember(items) { items.sumOf { it.units } }
    val purchasedUnits = remember(items) { items.filter { it.isPurchased }.sumOf { it.units } }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg),
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Shopping Cart",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AmazonOrange.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Life OS",
                                color = AmazonOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("shopping_cart_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAmazonLinkDialog = true },
                        modifier = Modifier.testTag("open_amazon_link_dialog_button")
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = "Paste Amazon Link",
                            tint = AmazonOrange
                        )
                    }
                    IconButton(
                        onClick = { showAddManualDialog = true },
                        modifier = Modifier.testTag("open_manual_add_dialog_button")
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add Item",
                            tint = CyanAccent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Multiple Lists Horizontal Selector Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "MY LISTS (${shoppingLists.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                TextButton(
                    onClick = { showCreateListDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("create_new_list_button")
                ) {
                    Icon(
                        Icons.Default.AddCircleOutline,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "New List",
                        color = CyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (shoppingLists.isEmpty()) {
                Surface(
                    onClick = { showCreateListDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "No custom lists yet. Tap to create one!",
                            color = TextMuted,
                            fontSize = 12.5.sp
                        )
                    }
                }
            } else {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shoppingLists, key = { it.id }) { list ->
                        val isSelected = list.id == activeList?.id
                        val borderColor = if (isSelected) CyanAccent else CardBorder
                        val bgColor = if (isSelected) CyanAccent.copy(alpha = 0.15f) else CardBg

                        Surface(
                            onClick = { viewModel.selectShoppingList(list.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = bgColor,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .animateContentSize()
                                .testTag("shopping_list_tab_${list.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(text = list.icon, fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = list.name,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp
                                )
                                if (isSelected) {
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = {
                                            listToEdit = list
                                            showEditListDialog = true
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = "Edit List",
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 2. Total Tracking Card
            activeList?.let { currentList ->
                ShoppingTotalTrackingCard(
                    list = currentList,
                    totalCost = totalCost,
                    purchasedCost = purchasedCost,
                    remainingCost = remainingCost,
                    totalUnits = totalUnits,
                    purchasedUnits = purchasedUnits,
                    totalItemsCount = items.size,
                    onOpenAddManual = { showAddManualDialog = true },
                    onOpenAmazonLink = { showAmazonLinkDialog = true }
                )
            }

            Spacer(Modifier.height(10.dp))

            // 3. Full-Width Search Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CardBg,
                border = BorderStroke(1.dp, if (searchQuery.isNotEmpty()) CyanAccent else CardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("shopping_search_field")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = if (searchQuery.isNotEmpty()) CyanAccent else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search items, notes, or categories...",
                                fontSize = 13.5.sp,
                                color = TextMuted
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 13.5.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Dedicated Filter Chips Row (Scrollable, never squeezed)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // All Chip
                val isAllSelected = currentFilter == ShoppingTabFilter.ALL
                Surface(
                    onClick = { currentFilter = ShoppingTabFilter.ALL },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isAllSelected) CyanAccent.copy(alpha = 0.2f) else CardBg,
                    border = BorderStroke(1.dp, if (isAllSelected) CyanAccent else CardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "All",
                            color = if (isAllSelected) CyanAccent else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isAllSelected) CyanAccent else Color.White.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${items.size}",
                                color = if (isAllSelected) Color.Black else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // To Buy Chip
                val isToBuySelected = currentFilter == ShoppingTabFilter.TO_BUY
                val toBuyCount = items.count { !it.isPurchased }
                Surface(
                    onClick = { currentFilter = ShoppingTabFilter.TO_BUY },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isToBuySelected) AmazonOrange.copy(alpha = 0.2f) else CardBg,
                    border = BorderStroke(1.dp, if (isToBuySelected) AmazonOrange else CardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "To Buy",
                            color = if (isToBuySelected) AmazonOrange else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isToBuySelected) FontWeight.Bold else FontWeight.Medium
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isToBuySelected) AmazonOrange else Color.White.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "$toBuyCount",
                                color = if (isToBuySelected) Color.Black else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // In Cart / Purchased Chip
                val isInCartSelected = currentFilter == ShoppingTabFilter.IN_CART
                val inCartCount = items.count { it.isPurchased }
                Surface(
                    onClick = { currentFilter = ShoppingTabFilter.IN_CART },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isInCartSelected) GreenAccent.copy(alpha = 0.2f) else CardBg,
                    border = BorderStroke(1.dp, if (isInCartSelected) GreenAccent else CardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Purchased",
                            color = if (isInCartSelected) GreenAccent else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isInCartSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isInCartSelected) GreenAccent else Color.White.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "$inCartCount",
                                color = if (isInCartSelected) Color.Black else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 4. Shopping Items List
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (items.isEmpty()) {
                        // Engaging high-converting modern empty state
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Glowing Hero Cart Icon Orb
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(
                                                AmazonOrange.copy(alpha = 0.35f),
                                                CyanAccent.copy(alpha = 0.15f),
                                                Color(0xFF141522)
                                            )
                                        )
                                    )
                                    .border(
                                        1.5.dp,
                                        Brush.sweepGradient(
                                            listOf(
                                                AmazonOrange,
                                                CyanAccent,
                                                AmazonOrange.copy(alpha = 0.3f),
                                                AmazonOrange
                                            )
                                        ),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingCart,
                                    contentDescription = null,
                                    tint = AmazonOrange,
                                    modifier = Modifier.size(38.dp)
                                )
                            }

                            // Catchy Header & Subtitle
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Your Smart Cart is Ready",
                                    color = Color.White,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Text(
                                    text = "Track budgets, estimate totals, and auto-import Amazon products with live pricing.",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }

                            Spacer(Modifier.height(2.dp))

                            // Action Card 1: Paste Amazon Link (Hero Primary Action)
                            Surface(
                                onClick = { showAmazonLinkDialog = true },
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF1C1610),
                                border = BorderStroke(1.dp, AmazonOrange.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("empty_state_paste_amazon_button")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    AmazonOrange.copy(alpha = 0.12f),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(AmazonOrange),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Link,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Paste Amazon Link",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.5.sp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(AmazonOrange.copy(alpha = 0.2f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = "AUTO",
                                                    color = AmazonOrange,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "Auto-fetches item title, price & photo",
                                            color = Color(0xFFFFB74D),
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = AmazonOrange,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Action Card 2: Add Item Manually (Secondary Action)
                            Surface(
                                onClick = { showAddManualDialog = true },
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF101720),
                                border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("empty_state_manual_add_button")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    CyanAccent.copy(alpha = 0.12f),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(CyanAccent.copy(alpha = 0.2f))
                                            .border(1.dp, CyanAccent, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = null,
                                            tint = CyanAccent,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Add Item Manually",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "Custom title, cost, quantity & category",
                                            color = CyanAccent.copy(alpha = 0.85f),
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        contentDescription = null,
                                        tint = CyanAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(2.dp))

                            // Features highlight footer
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(CardBg)
                                    .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = GreenAccent,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text("Budgeting", color = TextMuted, fontSize = 11.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(3.dp)
                                        .clip(CircleShape)
                                        .background(TextMuted)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = AmazonOrange,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text("Instant Sync", color = TextMuted, fontSize = 11.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(3.dp)
                                        .clip(CircleShape)
                                        .background(TextMuted)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = CyanAccent,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text("Checklist", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        // Filter / search yielded 0 items
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(CardBg)
                                    .border(1.dp, CardBorder, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Text(
                                text = "No items match your filter",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (searchQuery.isNotEmpty())
                                    "No items found matching \"$searchQuery\"."
                                else "No items currently match the selected filter.",
                                color = TextMuted,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    searchQuery = ""
                                    currentFilter = ShoppingTabFilter.ALL
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CardBorder),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Reset Filters", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shopping_items_lazy_column"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        ShoppingItemCard(
                            item = item,
                            onTogglePurchased = { viewModel.toggleShoppingItemPurchased(item) },
                            onIncrementUnits = { viewModel.updateShoppingItemUnits(item, 1) },
                            onDecrementUnits = { viewModel.updateShoppingItemUnits(item, -1) },
                            onEdit = {
                                itemToEdit = item
                                showEditItemDialog = true
                            },
                            onDelete = { viewModel.deleteShoppingItem(item) },
                            onOpenUrl = {
                                if (item.productUrl.isNotEmpty()) {
                                    try {
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.productUrl))
                                        context.startActivity(browserIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }

                    // Clear checked items button if any
                    val purchasedCount = items.count { it.isPurchased }
                    if (purchasedCount > 0) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = {
                                        activeList?.let { viewModel.clearPurchasedItems(it.id) }
                                    },
                                    modifier = Modifier.testTag("clear_purchased_items_button")
                                ) {
                                    Icon(
                                        Icons.Default.DeleteSweep,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Clear $purchasedCount Purchased Item${if (purchasedCount > 1) "s" else ""}",
                                        color = TextMuted,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // Dialogs
    // ==========================================

    // 1. Create New List Dialog
    if (showCreateListDialog) {
        CreateOrEditListDialog(
            initialList = null,
            onDismiss = { showCreateListDialog = false },
            onConfirm = { name, icon, budget ->
                viewModel.createShoppingList(name, icon, "#00E5FF", budget)
                showCreateListDialog = false
            }
        )
    }

    // 2. Edit List Dialog
    if (showEditListDialog && listToEdit != null) {
        CreateOrEditListDialog(
            initialList = listToEdit,
            onDismiss = {
                showEditListDialog = false
                listToEdit = null
            },
            onConfirm = { name, icon, budget ->
                listToEdit?.let {
                    viewModel.updateShoppingList(it.copy(name = name, icon = icon, budget = budget))
                }
                showEditListDialog = false
                listToEdit = null
            },
            onDelete = {
                listToEdit?.let { viewModel.deleteShoppingList(it) }
                showEditListDialog = false
                listToEdit = null
            }
        )
    }

    // 3. Manual Add Item Dialog
    if (showAddManualDialog) {
        AddManualItemDialog(
            activeListId = activeList?.id ?: "",
            onDismiss = { showAddManualDialog = false },
            onAdd = { name, cost, units, category, notes, imageUrl ->
                viewModel.addShoppingItem(
                    listId = activeList?.id ?: "",
                    name = name,
                    cost = cost,
                    units = units,
                    imageUrl = imageUrl,
                    category = category,
                    notes = notes
                )
                showAddManualDialog = false
                Toast.makeText(context, "Item added to cart!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 4. Paste Amazon Link Dialog
    if (showAmazonLinkDialog) {
        PasteAmazonLinkDialog(
            activeListId = activeList?.id ?: "",
            isParsing = isParsingAmazon,
            parsedProduct = parsedProduct,
            onParse = { url -> viewModel.parseAmazonUrl(url) },
            onClearParsed = { viewModel.clearParsedAmazonProduct() },
            onDismiss = {
                viewModel.clearParsedAmazonProduct()
                showAmazonLinkDialog = false
            },
            onAddProduct = { displayName, cost, units, imageUrl, productUrl, category ->
                viewModel.addShoppingItem(
                    listId = activeList?.id ?: "",
                    name = displayName,
                    cost = cost,
                    units = units,
                    imageUrl = imageUrl,
                    productUrl = productUrl,
                    category = category
                )
                viewModel.clearParsedAmazonProduct()
                showAmazonLinkDialog = false
                Toast.makeText(context, "Added to cart!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 5. Edit Existing Item Dialog (Editable Display Name, cost, units, notes)
    if (showEditItemDialog && itemToEdit != null) {
        EditItemDialog(
            item = itemToEdit!!,
            onDismiss = {
                showEditItemDialog = false
                itemToEdit = null
            },
            onSave = { updatedItem ->
                viewModel.updateShoppingItem(updatedItem)
                showEditItemDialog = false
                itemToEdit = null
            },
            onDelete = {
                viewModel.deleteShoppingItem(itemToEdit!!)
                showEditItemDialog = false
                itemToEdit = null
            }
        )
    }
}

// ==========================================
// Total Tracking Header Card
// ==========================================
@Composable
fun ShoppingTotalTrackingCard(
    list: ShoppingList,
    totalCost: Double,
    purchasedCost: Double,
    remainingCost: Double,
    totalUnits: Int,
    purchasedUnits: Int,
    totalItemsCount: Int,
    onOpenAddManual: () -> Unit,
    onOpenAmazonLink: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("shopping_total_tracking_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top Row: List Title & Units
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = list.icon, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = list.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E2032))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$totalUnits units · $totalItemsCount items",
                        color = CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Middle: Grand Total Highlight
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "TOTAL ESTIMATED COST",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = currencyFormat.format(totalCost),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // Quick Action Mini Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onOpenAmazonLink,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AmazonOrange.copy(alpha = 0.2f),
                            contentColor = AmazonOrange
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("total_card_paste_amazon_button")
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Amazon", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = onOpenAddManual,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = CyanAccent.copy(alpha = 0.2f),
                            contentColor = CyanAccent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("total_card_add_manual_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Manual", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Divider(
                color = CardBorder,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Subtotals: In Cart vs Remaining
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GreenAccent)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "In Cart / Bought: ${currencyFormat.format(purchasedCost)}",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AmazonOrange)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "To Buy: ${currencyFormat.format(remainingCost)}",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Optional Budget Tracker
            if (list.budget > 0.0) {
                val budgetFraction = (totalCost / list.budget).toFloat().coerceIn(0f, 1f)
                val isOverBudget = totalCost > list.budget
                val progressColor = when {
                    isOverBudget -> Color(0xFFFF5252)
                    budgetFraction > 0.8f -> Color(0xFFFFB300)
                    else -> CyanAccent
                }

                Spacer(Modifier.height(10.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Budget: ${currencyFormat.format(list.budget)}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Text(
                            text = if (isOverBudget) "Over budget by ${currencyFormat.format(totalCost - list.budget)}!"
                            else "${((1 - (totalCost / list.budget)) * 100).toInt()}% remaining",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = progressColor
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { budgetFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = progressColor,
                        trackColor = Color(0xFF24263A)
                    )
                }
            }
        }
    }
}

// ==========================================
// Item Card Component
// ==========================================
@Composable
fun ShoppingItemCard(
    item: ShoppingItem,
    onTogglePurchased: () -> Unit,
    onIncrementUnits: () -> Unit,
    onDecrementUnits: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.US)
    val isChecked = item.isPurchased

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked) Color(0xFF10111A) else CardBg
        ),
        border = BorderStroke(
            1.dp,
            if (isChecked) Color(0xFF1C1D2A) else CardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shopping_item_${item.id}")
            .clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox / Bought Toggle
            IconButton(
                onClick = onTogglePurchased,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("toggle_item_purchased_${item.id}")
            ) {
                if (isChecked) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Purchased",
                        tint = GreenAccent,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.RadioButtonUnchecked,
                        contentDescription = "Not Purchased",
                        tint = TextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Product Image (Thumbnail)
            if (item.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(2.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E2032)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (item.productUrl.contains("amazon", ignoreCase = true)) Icons.Default.ShoppingCart else Icons.Default.ShoppingBag,
                        contentDescription = null,
                        tint = if (item.productUrl.contains("amazon", ignoreCase = true)) AmazonOrange else CyanAccent,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Name, Category, Price & Units
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.name,
                        color = if (isChecked) TextMuted else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.productUrl.isNotBlank()) {
                        IconButton(
                            onClick = onOpenUrl,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open Amazon Link",
                                tint = AmazonOrange,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Category Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E2032))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = item.category,
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Price breakdown
                    Text(
                        text = if (item.units > 1)
                            "${currencyFormat.format(item.cost)} ea · Total: ${currencyFormat.format(item.totalCost)}"
                        else currencyFormat.format(item.totalCost),
                        color = if (isChecked) TextMuted else CyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (item.notes.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.notes,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Units Counter [-] [Units] [+]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1B1D2C))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                IconButton(
                    onClick = onDecrementUnits,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("decrement_units_${item.id}")
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Decrease Units",
                        tint = if (item.units > 1) Color.White else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Text(
                    text = "${item.units}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                IconButton(
                    onClick = onIncrementUnits,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("increment_units_${item.id}")
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Increase Units",
                        tint = CyanAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ==========================================
// Paste Amazon Link Dialog
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteAmazonLinkDialog(
    activeListId: String,
    isParsing: Boolean,
    parsedProduct: com.example.util.ParsedAmazonProduct?,
    onParse: (String) -> Unit,
    onClearParsed: () -> Unit,
    onDismiss: () -> Unit,
    onAddProduct: (displayName: String, cost: Double, units: Int, imageUrl: String, productUrl: String, category: String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var urlText by remember { mutableStateOf("") }
    var editableDisplayName by remember { mutableStateOf("") }
    var editableCostText by remember { mutableStateOf("") }
    var unitsCount by remember { mutableIntStateOf(1) }
    var categoryText by remember { mutableStateOf("Electronics") }
    var imageUrlText by remember { mutableStateOf("") }

    // When a product is parsed, pre-populate editable fields
    LaunchedEffect(parsedProduct) {
        if (parsedProduct != null) {
            editableDisplayName = parsedProduct.title
            editableCostText = if (parsedProduct.cost > 0.0) String.format(Locale.US, "%.2f", parsedProduct.cost) else ""
            imageUrlText = parsedProduct.imageUrl
        }
    }

    // Check clipboard automatically for convenience
    LaunchedEffect(Unit) {
        val clip = clipboard.getText()?.text?.trim() ?: ""
        if (AmazonLinkParser.isAmazonUrl(clip) && urlText.isBlank()) {
            urlText = clip
            onParse(clip)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AmazonOrange),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Import from Amazon", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Paste link to auto-fetch image, cost & name", fontSize = 11.sp, color = TextMuted)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // URL input row
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                    },
                    label = { Text("Paste Amazon Link") },
                    placeholder = { Text("https://www.amazon.com/dp/...") },
                    singleLine = false,
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = clipboard.getText()?.text?.trim() ?: ""
                            if (clip.isNotEmpty()) {
                                urlText = clip
                                onParse(clip)
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = AmazonOrange)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmazonOrange,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("amazon_link_input_field")
                )

                // Fetch Button
                Button(
                    onClick = {
                        if (urlText.isNotBlank()) {
                            onParse(urlText)
                        }
                    },
                    enabled = urlText.isNotBlank() && !isParsing,
                    colors = ButtonDefaults.buttonColors(containerColor = AmazonOrange),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("fetch_amazon_details_button")
                ) {
                    if (isParsing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Analyzing Amazon Product...", color = Color.Black, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Fetch Details", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                // Parsed Preview & Customization
                if (parsedProduct != null) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1D2D)),
                        border = BorderStroke(1.dp, AmazonOrange.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (imageUrlText.isNotBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(imageUrlText)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White)
                                            .padding(4.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF24263A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Image, contentDescription = null, tint = TextMuted)
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Product Detected",
                                        color = AmazonOrange,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (parsedProduct.cost > 0.0) "$${parsedProduct.cost}" else "Cost not detected, please enter below",
                                        color = if (parsedProduct.cost > 0.0) GreenAccent else TextMuted,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Divider(color = Color(0xFF2E3147), thickness = 1.dp)

                            // Editable Display Name (explicitly requested: user can change display name!)
                            OutlinedTextField(
                                value = editableDisplayName,
                                onValueChange = { editableDisplayName = it },
                                label = { Text("Display Name (Editable)") },
                                placeholder = { Text("Product name in cart") },
                                singleLine = false,
                                maxLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanAccent,
                                    unfocusedBorderColor = CardBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("amazon_editable_display_name_field")
                            )

                            // Cost and Units Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = editableCostText,
                                    onValueChange = { editableCostText = it },
                                    label = { Text("Unit Cost ($)") },
                                    placeholder = { Text("29.99") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CyanAccent,
                                        unfocusedBorderColor = CardBorder,
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("amazon_editable_cost_field")
                                )

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Units", fontSize = 11.sp, color = TextMuted)
                                    Spacer(Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF24263A))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { if (unitsCount > 1) unitsCount-- },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                        Text("$unitsCount", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                                        IconButton(
                                            onClick = { unitsCount++ },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cost = editableCostText.toDoubleOrNull() ?: 0.0
                    val displayName = editableDisplayName.ifBlank { parsedProduct?.title ?: "Amazon Item" }
                    onAddProduct(
                        displayName,
                        cost,
                        unitsCount,
                        imageUrlText,
                        urlText,
                        categoryText
                    )
                },
                enabled = parsedProduct != null || urlText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("add_parsed_amazon_product_button")
            ) {
                Text("Add to List", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        containerColor = CardBg
    )
}

// ==========================================
// Add Manual Item Dialog
// ==========================================
@Composable
fun AddManualItemDialog(
    activeListId: String,
    onDismiss: () -> Unit,
    onAdd: (name: String, cost: Double, units: Int, category: String, notes: String, imageUrl: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var units by remember { mutableIntStateOf(1) }
    var category by remember { mutableStateOf("Groceries") }
    var notes by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }

    val categories = listOf("Groceries", "Electronics", "Home & Kitchen", "Clothing", "Personal Care", "Books", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Add Item Manually", fontWeight = FontWeight.Bold, color = Color.White)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item Name *") },
                    placeholder = { Text("e.g. Organic Almond Milk") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_item_name_field")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Cost ($) *") },
                        placeholder = { Text("4.99") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_item_cost_field")
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Units", fontSize = 11.sp, color = TextMuted)
                        Spacer(Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E2032))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = { if (units > 1) units-- },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            Text("$units", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                            IconButton(
                                onClick = { units++ },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Category selection chips
                Text("Category", fontSize = 12.sp, color = TextMuted)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanAccent.copy(alpha = 0.2f),
                                selectedLabelColor = CyanAccent
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Brand / Specs (Optional)") },
                    placeholder = { Text("e.g. 1-Gallon unsweetened") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    label = { Text("Image URL (Optional)") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cost = costText.toDoubleOrNull() ?: 0.0
                    onAdd(name, cost, units, category, notes, imageUrl)
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("submit_manual_item_button")
            ) {
                Text("Add Item", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        containerColor = CardBg
    )
}

// ==========================================
// Edit Item Dialog (Allows editing display name, cost, units)
// ==========================================
@Composable
fun EditItemDialog(
    item: ShoppingItem,
    onDismiss: () -> Unit,
    onSave: (ShoppingItem) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var costText by remember { mutableStateOf(String.format(Locale.US, "%.2f", item.cost)) }
    var units by remember { mutableIntStateOf(item.units) }
    var category by remember { mutableStateOf(item.category) }
    var notes by remember { mutableStateOf(item.notes) }
    var imageUrl by remember { mutableStateOf(item.imageUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Edit Item", fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Item", tint = Color(0xFFFF5252))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_item_name_field")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Cost ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("edit_item_cost_field")
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Units", fontSize = 11.sp, color = TextMuted)
                        Spacer(Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E2032))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = { if (units > 1) units-- },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            Text("$units", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                            IconButton(
                                onClick = { units++ },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Specs") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    label = { Text("Image URL") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedCost = costText.toDoubleOrNull() ?: item.cost
                    onSave(
                        item.copy(
                            name = name.ifBlank { item.name },
                            cost = parsedCost,
                            units = units,
                            category = category.ifBlank { item.category },
                            notes = notes,
                            imageUrl = imageUrl
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("save_edited_item_button")
            ) {
                Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        containerColor = CardBg
    )
}

// ==========================================
// Create or Edit Shopping List Dialog
// ==========================================
@Composable
fun CreateOrEditListDialog(
    initialList: ShoppingList?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String, budget: Double) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialList?.name ?: "") }
    var icon by remember { mutableStateOf(initialList?.icon ?: "🛒") }
    var budgetText by remember { mutableStateOf(if ((initialList?.budget ?: 0.0) > 0.0) "${initialList?.budget}" else "") }

    val icons = listOf("🛒", "🛍️", "🍎", "💻", "📦", "🎁", "💊", "👗", "🏠", "⚡")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (initialList == null) "New Shopping List" else "Edit List",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete List", tint = Color(0xFFFF5252))
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("List Name") },
                    placeholder = { Text("e.g. Amazon Wishlist, Tech Cart") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("list_name_input_field")
                )

                Text("Choose Icon", fontSize = 12.sp, color = TextMuted)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(icons) { ic ->
                        Surface(
                            onClick = { icon = ic },
                            shape = CircleShape,
                            color = if (icon == ic) CyanAccent.copy(alpha = 0.25f) else Color(0xFF1E2032),
                            border = if (icon == ic) BorderStroke(1.dp, CyanAccent) else null,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(ic, fontSize = 20.sp)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it },
                    label = { Text("Target Budget ($) (Optional)") },
                    placeholder = { Text("e.g. 200.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("list_budget_input_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val budget = budgetText.toDoubleOrNull() ?: 0.0
                    onConfirm(name, icon, budget)
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("submit_list_button")
            ) {
                Text(if (initialList == null) "Create" else "Save", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        containerColor = CardBg
    )
}
