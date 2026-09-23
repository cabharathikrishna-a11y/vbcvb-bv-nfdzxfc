package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.util.rememberVideoThumbnail
import com.example.util.rememberPdfFirstPagePreview
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.data.Contact
import com.example.ui.AppViewModel
import com.example.ui.theme.Charcoal
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.WaterBlue
import com.example.util.MediaCompressionHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import java.io.File

// Premium avatar constants
val AVATAR_OPTIONS = listOf(
    "https://api.dicebear.com/7.x/bottts/svg?seed=Felix",
    "https://api.dicebear.com/7.x/bottts/svg?seed=Anya",
    "https://api.dicebear.com/7.x/bottts/svg?seed=Leo",
    "https://api.dicebear.com/7.x/bottts/svg?seed=Dana",
    "https://api.dicebear.com/7.x/bottts/svg?seed=Jack"
)

enum class ContactScreen {
    LIST, ADD, EDIT
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsView(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val contacts by viewModel.contacts.collectAsState()
    val foldersList by viewModel.contactFolders.collectAsState()
    val context = LocalContext.current

    // Navigation and selection state
    var screenState by remember { mutableStateOf(ContactScreen.LIST) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    // Dialog triggering states for Folders
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var showRenameFolderDialog by remember { mutableStateOf(false) }
    var renameFolderOldName by remember { mutableStateOf("") }
    var renameFolderNewName by remember { mutableStateOf("") }

    var showDeleteFolderDialog by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf("") }

    // Dialog triggering states for Contacts
    var showContactActionDialog by remember { mutableStateOf(false) }
    var longPressedContact by remember { mutableStateOf<Contact?>(null) }
    var showConfirmDeleteContactDialog by remember { mutableStateOf(false) }

    // Form inputs state
    var firstName by remember { mutableStateOf("") }
    var middleName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var jobTitle by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var dobString by remember { mutableStateOf("") }
    var anniversaryString by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf("") }
    var selectedFolderOption by remember { mutableStateOf("All") }

    val customFieldsList = remember { mutableStateListOf<Pair<String, String>>() }
    var customFieldNameDraft by remember { mutableStateOf("") }
    var customFieldValueDraft by remember { mutableStateOf("") }

    val customDatesList = remember { mutableStateListOf<Pair<String, String>>() }
    var customDateNameDraft by remember { mutableStateOf("") }
    var customDateValueDraft by remember { mutableStateOf("") }

    val externalSelectedId by viewModel.selectedContactId.collectAsState()
    LaunchedEffect(externalSelectedId, contacts) {
        externalSelectedId?.let { idVal ->
            val found = contacts.find { it.id == idVal }
            if (found != null) {
                selectedContact = found
                screenState = ContactScreen.LIST
                viewModel.clearSelectedContactId()
            }
        }
    }
    var selectedFolder by remember { mutableStateOf("All") }
    var isFoldersSidebarExpanded by remember { mutableStateOf(false) }

    val handleBackPress = {
        if ((screenState == ContactScreen.ADD || screenState == ContactScreen.EDIT) && firstName.trim().isNotEmpty()) {
            showUnsavedDialog = true
        } else if (screenState != ContactScreen.LIST) {
            screenState = ContactScreen.LIST
        } else if (selectedContact != null) {
            selectedContact = null
        }
    }

    androidx.activity.compose.BackHandler(enabled = screenState != ContactScreen.LIST || selectedContact != null) {
        handleBackPress()
    }
    
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes", color = Color.White) },
            text = { Text("You have unsaved changes. Do you want to save or discard them?", color = Color.LightGray) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    if (firstName.trim().isNotEmpty()) {
                        val datesJson = customDatesList.joinToString(";") { "${it.first}:${it.second}" }
                        val fieldsJson = customFieldsList.joinToString(";") { "${it.first}:${it.second}" }
                        
                        val photoToSave = selectedAvatar.takeIf { it.isNotEmpty() }
                        if (screenState == ContactScreen.EDIT && selectedContact != null) {
                            // Update existing logic
                            val currentAttached = mutableListOf<String>()
                            if (selectedContact!!.attachedFilesJson.isNotEmpty()) {
                                try {
                                    val arr = org.json.JSONArray(selectedContact!!.attachedFilesJson)
                                    for (i in 0 until arr.length()) {
                                        val itm = arr.getString(i)
                                        if (itm.isNotBlank() && !currentAttached.contains(itm)) currentAttached.add(itm)
                                    }
                                } catch (_: Exception) {}
                            }
                            if (!photoToSave.isNullOrEmpty() && !currentAttached.contains(photoToSave)) {
                                currentAttached.add(0, photoToSave)
                            }
                            val updated = selectedContact!!.copy(
                                firstName = firstName.trim(),
                                middleName = middleName.trim(),
                                lastName = lastName.trim(),
                                jobTitle = jobTitle.trim(),
                                email = email.trim(),
                                address = address.trim(),
                                phone = phone.trim(),
                                dobString = dobString.trim(),
                                photoUri = photoToSave,
                                anniversaryString = anniversaryString.trim(),
                                additionalFieldsJson = fieldsJson,
                                additionalDatesJson = datesJson,
                                folder = selectedFolderOption,
                                attachedFilesJson = org.json.JSONArray(currentAttached).toString()
                            )
                            viewModel.updateContact(updated)
                        } else {
                            val currentAttached = mutableListOf<String>()
                            if (!photoToSave.isNullOrEmpty()) {
                                currentAttached.add(photoToSave)
                            }
                            viewModel.createContact(
                                firstName = firstName.trim(),
                                middleName = middleName.trim(),
                                lastName = lastName.trim(),
                                jobTitle = jobTitle.trim(),
                                email = email.trim(),
                                address = address.trim(),
                                phone = phone.trim(),
                                dobString = dobString.trim(),
                                photoUri = photoToSave,
                                anniversaryString = anniversaryString.trim(),
                                additionalFieldsJson = fieldsJson,
                                additionalDatesJson = datesJson,
                                folder = selectedFolderOption,
                                attachedFilesJson = org.json.JSONArray(currentAttached).toString()
                            )
                        }
                    }
                    screenState = ContactScreen.LIST
                }) {
                    Text("Save", color = WaterBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    screenState = ContactScreen.LIST
                }) {
                    Text("Discard", color = Color(0xFFF9325D))
                }
            }
        )
    }

    // Image Picker launcher (copy to files dir on pick)
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val destFile = com.example.util.InternalStorageManager.getFile(
                context, 
                com.example.util.InternalStorageManager.Category.CONTACTS, 
                "profile_${System.currentTimeMillis()}.jpg"
            )
            val compressSuccess = MediaCompressionHelper.compressImageFromUri(context, uri, destFile)
            if (compressSuccess) {
                selectedAvatar = destFile.absolutePath
                Toast.makeText(context, "Premium profile photo compressed & configured!", Toast.LENGTH_SHORT).show()
            } else {
                val copied = copyUriToLocalFile(context, uri, destFile.name)
                if (copied != null) {
                    selectedAvatar = copied.absolutePath
                    Toast.makeText(context, "Profile photo attached!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val contactsPrefs = remember { context.getSharedPreferences("app_contacts_prefs", android.content.Context.MODE_PRIVATE) }
    val isAuthorized = remember(context) {
        val account = com.example.util.GmsUtils.getLastSignedInAccount(context)
            ?: try { GoogleSignIn.getLastSignedInAccount(context) } catch (_: Throwable) { null }
        account != null && account.grantedScopes.any { it.scopeUri.contains("contacts", ignoreCase = true) }
    }
    var showContactsBanner by remember {
        mutableStateOf(false)
    }

    val authResolutionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.syncGoogleContacts(context) { intent ->
                // Do not loop infinitely if second auth fails
            }
        }
    }

    val contactsSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                if (!account.email.isNullOrBlank()) {
                    context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("selected_contacts_account", account.email)
                        .apply()
                }
                Toast.makeText(context, "Connected ${account.email}! Syncing contacts...", Toast.LENGTH_SHORT).show()
                viewModel.syncGoogleContacts(context) { intent ->
                    authResolutionLauncher.launch(intent)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Google sign-in cancelled or failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val triggerGoogleSignInForContacts = {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestScopes(
                    Scope("https://www.googleapis.com/auth/contacts"),
                    Scope("https://www.googleapis.com/auth/contacts.other.readonly")
                )
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            contactsSignInLauncher.launch(client.signInIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch Google Sign-In: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val performContactsRefresh = {
        Toast.makeText(context, "Refreshing contacts & photos...", Toast.LENGTH_SHORT).show()
        viewModel.loadContactFolders()
        viewModel.refreshAllContactPhotos(context)

        val account = com.example.util.GmsUtils.getLastSignedInAccount(context)
            ?: try { GoogleSignIn.getLastSignedInAccount(context) } catch (_: Throwable) { null }
        val hasScope = account != null && account.grantedScopes.any { it.scopeUri.contains("contacts", ignoreCase = true) }

        if (account != null || hasScope) {
            viewModel.syncGoogleContacts(context) { intent ->
                authResolutionLauncher.launch(intent)
            }
        } else {
            triggerGoogleSignInForContacts()
        }
    }

    LaunchedEffect(Unit) {
        val account = com.example.util.GmsUtils.getLastSignedInAccount(context)
            ?: try { GoogleSignIn.getLastSignedInAccount(context) } catch (_: Throwable) { null }
        val isAuth = account != null && account.grantedScopes.any { it.scopeUri.contains("contacts", ignoreCase = true) }
        if (isAuth) {
            viewModel.syncGoogleContacts(context) { }
        }
    }

    // Main screen controller
    when (screenState) {
        ContactScreen.LIST -> {
            val isTablet = LocalConfiguration.current.screenWidthDp >= 600
            val contactListUI = @Composable {
                    val sortedFilteredContacts = remember(contacts, selectedFolder) {
                        val base = if (selectedFolder == "All") {
                            contacts
                        } else {
                            contacts.filter { it.folder == selectedFolder }
                        }
                        base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { "${it.firstName} ${it.lastName}".trim() })
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Header inside List View
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 3-line Toggle Menu Button to toggle the folders list drawer
                            IconButton(
                                onClick = { isFoldersSidebarExpanded = !isFoldersSidebarExpanded },
                                modifier = Modifier.testTag("contacts_sidebar_toggle").padding(end = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Toggle Folders Sidebar",
                                    tint = Color.White
                                )
                            }

                            Text(
                                text = if (selectedFolder == "All") "All Contacts" else "📁 $selectedFolder",
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                fontSize = 18.sp,
                                modifier = Modifier.weight(1f)
                            )

                            val googleContactsSyncStatus by viewModel.googleContactsSyncStatus.collectAsState()

                            if (googleContactsSyncStatus == "Syncing...") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).padding(end = 12.dp),
                                    color = WaterBlue,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(
                                    onClick = { performContactsRefresh() },
                                    modifier = Modifier.padding(end = 8.dp).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = "Sync Google Contacts & Refresh",
                                        tint = WaterBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    firstName = ""
                                    middleName = ""
                                    lastName = ""
                                    jobTitle = ""
                                    email = ""
                                    address = ""
                                    phone = ""
                                    dobString = ""
                                    anniversaryString = ""
                                    customFieldNameDraft = ""
                                    customFieldValueDraft = ""
                                    customFieldsList.clear()
                                    customDateNameDraft = ""
                                    customDateValueDraft = ""
                                    customDatesList.clear()
                                    selectedAvatar = AVATAR_OPTIONS.random()
                                    selectedFolderOption = if (selectedFolder == "All") "All" else selectedFolder
                                    screenState = ContactScreen.ADD
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(WaterBlue)
                                    .testTag("add_contact_fab")
                                    .size(36.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Contact", tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                        }

                        if (showContactsBanner) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141419)),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, WaterBlue.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CloudSync,
                                                contentDescription = "Sync",
                                                tint = WaterBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Connect Google Contacts",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                contactsPrefs.edit().putBoolean("contacts_connect_banner_dismissed", true).apply()
                                                showContactsBanner = false
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Dismiss",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Sync and back up all your custom contacts with the official Google Contacts Cloud API.",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                contactsPrefs.edit().putBoolean("contacts_connect_banner_dismissed", true).apply()
                                                showContactsBanner = false
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("No thanks", color = Color.Gray, fontSize = 11.sp)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                contactsPrefs.edit().putBoolean("contacts_connect_banner_dismissed", true).apply()
                                                showContactsBanner = false
                                                viewModel.syncGoogleContacts(context) { intent ->
                                                    authResolutionLauncher.launch(intent)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("Connect", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        if (sortedFilteredContacts.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBox,
                                        contentDescription = null,
                                        tint = Color.Gray.copy(alpha = 0.6f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No contacts in fold.",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Click the '+' button above to register premium contacts.",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(sortedFilteredContacts, key = { idx, contact -> "${contact.id}_${contact.phone}_$idx" }) { _, contact ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Color.Transparent, RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = { selectedContact = contact },
                                                onLongClick = {
                                                    longPressedContact = contact
                                                    showContactActionDialog = true
                                                }
                                            ),
                                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Profile picture layout (Circle)
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(listOf(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF009688), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFF795548))[(contact.firstName.firstOrNull()?.code ?: 0) % 11].copy(alpha = 0.8f))
                                                    .border(1.dp, WaterBlue.copy(alpha = 0.5f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${contact.firstName.firstOrNull()?.uppercaseChar() ?: '?'}",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp
                                                )
                                                if (!contact.photoUri.isNullOrEmpty()) {
                                                    val imageModel = remember(contact.photoUri) {
                                                        val raw = contact.photoUri?.trim()
                                                        when {
                                                            raw.isNullOrEmpty() -> null
                                                            raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
                                                            raw.startsWith("http://") || raw.startsWith("https://") -> raw
                                                            else -> {
                                                                val f = File(raw)
                                                                if (f.exists()) f else raw
                                                            }
                                                        }
                                                    }
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(imageModel)
                                                            .crossfade(true)
                                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                                            .diskCachePolicy(CachePolicy.ENABLED)
                                                            .build(),
                                                        contentDescription = "Profile Photo",
                                                        modifier = Modifier.clip(CircleShape).fillMaxSize(),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                val fullName = remember(contact.firstName, contact.middleName, contact.lastName) {
                                                    listOf(contact.firstName, contact.middleName, contact.lastName)
                                                        .filter { it.isNotBlank() }
                                                        .joinToString(" ")
                                                        .ifBlank { "Unnamed Contact" }
                                                }
                                                // 1. One line name
                                                Text(
                                                    text = fullName,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )

                                                Spacer(modifier = Modifier.height(4.dp))

                                                // 2. Second line: Bubble design with folder name, and logos side by side beside it
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    // Folder name inside bubble design
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = WaterBlue.copy(alpha = 0.15f),
                                                        border = BorderStroke(0.5.dp, WaterBlue.copy(alpha = 0.45f))
                                                    ) {
                                                        Text(
                                                            text = contact.folder.ifBlank { "All" },
                                                            color = WaterBlue,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }

                                                    // 4. Cake icon beside folder if birthday is saved
                                                    if (contact.dobString.isNotBlank()) {
                                                        Icon(
                                                            imageVector = Icons.Default.Cake,
                                                            contentDescription = "Birthday: ${contact.dobString}",
                                                            tint = Color(0xFFFF80AB),
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }

                                                    // 5. Attached social media logos beside it
                                                    val socialLogos = remember(contact.additionalFieldsJson) {
                                                        com.example.util.ContactSocialHelper.getSocialLogosForContact(contact.additionalFieldsJson)
                                                    }
                                                    for (brand in socialLogos) {
                                                        when (brand) {
                                                            com.example.util.ContactSocialHelper.SocialBrand.INSTAGRAM -> {
                                                                Image(
                                                                    painter = painterResource(R.drawable.ic_instagram_shortcut),
                                                                    contentDescription = "Instagram",
                                                                    modifier = Modifier.size(15.dp)
                                                                )
                                                            }
                                                            com.example.util.ContactSocialHelper.SocialBrand.SNAPCHAT -> {
                                                                Image(
                                                                    painter = painterResource(R.drawable.ic_snapchat_logo),
                                                                    contentDescription = "Snapchat",
                                                                    modifier = Modifier.size(15.dp)
                                                                )
                                                            }
                                                            com.example.util.ContactSocialHelper.SocialBrand.YOUTUBE -> {
                                                                Image(
                                                                    painter = painterResource(R.drawable.ic_youtube_shortcut),
                                                                    contentDescription = "YouTube",
                                                                    modifier = Modifier.size(15.dp)
                                                                )
                                                            }
                                                            com.example.util.ContactSocialHelper.SocialBrand.TWITTER -> {
                                                                Icon(
                                                                    imageVector = Icons.Default.AlternateEmail,
                                                                    contentDescription = "Twitter / X",
                                                                    tint = Color(0xFF1DA1F2),
                                                                    modifier = Modifier.size(15.dp)
                                                                )
                                                            }
                                                            com.example.util.ContactSocialHelper.SocialBrand.TELEGRAM -> {
                                                                Icon(
                                                                    imageVector = Icons.Default.Send,
                                                                    contentDescription = "Telegram",
                                                                    tint = Color(0xFF0088CC),
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                            com.example.util.ContactSocialHelper.SocialBrand.FACEBOOK -> {
                                                                Icon(
                                                                    imageVector = Icons.Default.ThumbUp,
                                                                    contentDescription = "Facebook",
                                                                    tint = Color(0xFF1877F2),
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                            com.example.util.ContactSocialHelper.SocialBrand.LINKEDIN -> {
                                                                Icon(
                                                                    imageVector = Icons.Default.Work,
                                                                    contentDescription = "LinkedIn",
                                                                    tint = Color(0xFF0A66C2),
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                            com.example.util.ContactSocialHelper.SocialBrand.GENERAL_LINK -> {
                                                                Icon(
                                                                    imageVector = Icons.Default.Link,
                                                                    contentDescription = "Link",
                                                                    tint = WaterBlue,
                                                                    modifier = Modifier.size(15.dp)
                                                                )
                                                            }
                                                        }
                                                    }

                                                    // 6. WhatsApp logo at last if phone number attached
                                                    if (contact.phone.isNotBlank()) {
                                                        Image(
                                                            painter = painterResource(R.drawable.ic_whatsapp_logo),
                                                            contentDescription = "WhatsApp: ${contact.phone}",
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

            val contactDetailsUI = @Composable { contact: Contact ->
                    // Show Contact Details View (Redesigned as separate Full-Screen View)
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        val attachedFiles = remember(contact.attachedFilesJson, contact.photoUri) {
                            val res = mutableListOf<String>()
                            if (contact.attachedFilesJson.isNotEmpty()) {
                                try {
                                    val arr = org.json.JSONArray(contact.attachedFilesJson)
                                    for (i in 0 until arr.length()) {
                                        val itm = arr.getString(i)
                                        if (itm.isNotBlank() && !res.contains(itm)) res.add(itm)
                                    }
                                } catch (_: Exception) {}
                            }
                            val photo = contact.photoUri?.trim()
                            if (!photo.isNullOrEmpty() && !res.contains(photo)) {
                                res.add(0, photo)
                            }
                            res
                        }

                        // Auto-persist contact profile pic into attachedFilesJson in database
                        LaunchedEffect(contact.id, contact.photoUri) {
                            val photo = contact.photoUri?.trim()
                            if (!photo.isNullOrEmpty()) {
                                val currentList = mutableListOf<String>()
                                if (contact.attachedFilesJson.isNotEmpty()) {
                                    try {
                                        val arr = org.json.JSONArray(contact.attachedFilesJson)
                                        for (i in 0 until arr.length()) {
                                            val itm = arr.getString(i)
                                            if (itm.isNotBlank() && !currentList.contains(itm)) currentList.add(itm)
                                        }
                                    } catch (_: Exception) {}
                                }
                                if (!currentList.contains(photo)) {
                                    currentList.add(0, photo)
                                    val updated = contact.copy(attachedFilesJson = org.json.JSONArray(currentList).toString())
                                    viewModel.updateContact(updated)
                                    selectedContact = updated
                                }
                            }
                        }

                        // Top bar inside Detail Screen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selectedContact = null }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back to list", tint = Color.White)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // EDIT button
                                IconButton(
                                    onClick = {
                                        longPressedContact = contact
                                        // Load form entries to go to edit screen
                                        firstName = contact.firstName
                                        middleName = contact.middleName
                                        lastName = contact.lastName
                                        jobTitle = contact.jobTitle
                                        email = contact.email
                                        address = contact.address
                                        phone = contact.phone
                                        dobString = contact.dobString
                                        anniversaryString = contact.anniversaryString
                                        selectedAvatar = contact.photoUri ?: ""
                                        selectedFolderOption = contact.folder

                                        customFieldsList.clear()
                                        if (contact.additionalFieldsJson.isNotEmpty()) {
                                            val parsed = com.example.util.ContactSocialHelper.parseCustomFields(contact.additionalFieldsJson)
                                            customFieldsList.addAll(parsed)
                                        }

                                        customDatesList.clear()
                                        if (contact.additionalDatesJson.isNotEmpty()) {
                                            contact.additionalDatesJson.split(";").forEach { pair ->
                                                val parts = pair.split(":")
                                                if (parts.size == 2) customDatesList.add(parts[0] to parts[1])
                                            }
                                        }

                                        screenState = ContactScreen.EDIT
                                    }
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Contact", tint = WaterBlue)
                                }

                                // DELETE button
                                IconButton(
                                    onClick = {
                                        longPressedContact = contact
                                        showConfirmDeleteContactDialog = true
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Contact", tint = Color.Red)
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                // Header Box (Profile Image + Quick Communication)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .background(listOf(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF009688), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFF795548))[(contact.firstName.firstOrNull()?.code ?: 0) % 11].copy(alpha = 0.8f))
                                            .border(2.dp, WaterBlue, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${contact.firstName.firstOrNull()?.uppercaseChar() ?: '?'}",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 24.sp
                                        )
                                        if (!contact.photoUri.isNullOrEmpty()) {
                                            val imageModel = remember(contact.photoUri) {
                                                val raw = contact.photoUri?.trim()
                                                when {
                                                    raw.isNullOrEmpty() -> null
                                                    raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
                                                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                                                    else -> {
                                                        val f = File(raw)
                                                        if (f.exists()) f else raw
                                                    }
                                                }
                                            }
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(imageModel)
                                                    .crossfade(true)
                                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                                    .diskCachePolicy(CachePolicy.ENABLED)
                                                    .build(),
                                                contentDescription = "Profile Photo",
                                                modifier = Modifier.clip(CircleShape).fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }

                                    // OS direct Action triggers
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (contact.phone.isNotBlank()) {
                                            IconButton(
                                                onClick = {
                                                    com.example.util.ContactSocialHelper.openWhatsApp(context, contact.phone)
                                                },
                                                modifier = Modifier.clip(CircleShape).background(Color(0xFF25D366).copy(alpha = 0.18f))
                                            ) {
                                                Icon(Icons.Default.Chat, contentDescription = "WhatsApp Redirection", tint = Color(0xFF25D366))
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}"))
                                                context.startActivity(dialIntent)
                                            },
                                            modifier = Modifier.clip(CircleShape).background(WaterBlue.copy(alpha = 0.15f))
                                        ) {
                                            Icon(Icons.Default.Phone, contentDescription = "Call", tint = WaterBlue)
                                        }

                                        IconButton(
                                            onClick = {
                                                val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.phone}"))
                                                context.startActivity(smsIntent)
                                            },
                                            modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f))
                                        ) {
                                            Icon(Icons.Default.Email, contentDescription = "SMS", tint = Color.White)
                                        }
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${contact.firstName} ${if (contact.middleName.isNotEmpty()) contact.middleName + " " else ""}${contact.lastName}",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    color = Color.White
                                )
                                if (contact.jobTitle.isNotEmpty()) {
                                    Text(contact.jobTitle, color = WaterBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Divider(color = Color.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))
                            }

                            // Custom date-tags count detail rows
                            item { ContactDetailRow(Icons.Default.LocalAirport, "Folder", contact.folder) }
                            item { ContactDetailRow(Icons.Default.Phone, "Phone", contact.phone) }
                            item { ContactDetailRow(Icons.Default.Email, "Email", contact.email.ifEmpty { "Not added" }) }
                            item { ContactDetailRow(Icons.Default.LocationOn, "Address", contact.address.ifEmpty { "Not added" }) }
                            item { ContactDetailRow(Icons.Default.DateRange, "Birthday (DOB)", contact.dobString.ifEmpty { "Not added" }) }
                            item { ContactDetailRow(Icons.Default.Favorite, "Anniversary", contact.anniversaryString.ifEmpty { "Not added" }) }

                            if (contact.additionalDatesJson.isNotEmpty()) {
                                contact.additionalDatesJson.split(";").forEach { pair ->
                                    val parts = pair.split(":")
                                    if (parts.size == 2) {
                                        item { ContactDetailRow(Icons.Default.Event, parts[0], parts[1]) }
                                    }
                                }
                            }

                            if (contact.additionalFieldsJson.isNotEmpty()) {
                                val rawCustomFields = com.example.util.ContactSocialHelper.parseCustomFields(contact.additionalFieldsJson)
                                val deduplicatedFields = com.example.util.ContactSocialHelper.deduplicateCustomFields(rawCustomFields)
                                val hasInstagramField = deduplicatedFields.any {
                                    it.type == com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_ID ||
                                    it.type == com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_LINK
                                }

                                if (!hasInstagramField && contact.phone.isNotBlank()) {
                                    item {
                                        WhatsAppRedirectionCard(phoneNumber = contact.phone)
                                    }
                                }

                                var renderedWaAboveInsta = false
                                deduplicatedFields.forEach { recognized ->
                                    val isInsta = recognized.type == com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_ID ||
                                                  recognized.type == com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_LINK
                                    val showWaAboveThis = isInsta && !renderedWaAboveInsta && contact.phone.isNotBlank()
                                    if (showWaAboveThis) {
                                        renderedWaAboveInsta = true
                                    }
                                    item {
                                        ContactCustomFieldRow(
                                            recognized = recognized,
                                            phoneNumber = if (showWaAboveThis) contact.phone else null
                                        )
                                    }
                                }
                            } else if (contact.phone.isNotBlank()) {
                                item {
                                    WhatsAppRedirectionCard(phoneNumber = contact.phone)
                                }
                            }

                            // 4. Files and Photos Section (Includes profile picture and attached media/docs)
                            item {
                                Divider(color = Color.Gray.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))
                                
                                val photoPickerLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.GetContent()
                                ) { uri: Uri? ->
                                    if (uri != null) {
                                        val originalName = getFileName(context, uri)
                                        val localName = "photo_${System.currentTimeMillis()}_${originalName}"
                                        val copiedFile = copyUriToLocalFile(context, uri, localName)
                                        if (copiedFile != null) {
                                            val currentList = mutableListOf<String>()
                                            if (contact.attachedFilesJson.isNotEmpty()) {
                                                try {
                                                    val arr = org.json.JSONArray(contact.attachedFilesJson)
                                                    for (i in 0 until arr.length()) { currentList.add(arr.getString(i)) }
                                                } catch (e: Exception) {}
                                            }
                                            currentList.add(copiedFile.absolutePath)
                                            val updated = contact.copy(attachedFilesJson = org.json.JSONArray(currentList).toString())
                                            viewModel.updateContact(updated)
                                            selectedContact = updated
                                            Toast.makeText(context, "Photo attached successfully!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }

                                val docPickerLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.GetContent()
                                ) { uri: Uri? ->
                                    if (uri != null) {
                                        val originalName = getFileName(context, uri)
                                        val localName = "doc_${System.currentTimeMillis()}_${originalName}"
                                        val copiedFile = copyUriToLocalFile(context, uri, localName)
                                        if (copiedFile != null) {
                                            val currentList = mutableListOf<String>()
                                            if (contact.attachedFilesJson.isNotEmpty()) {
                                                try {
                                                    val arr = org.json.JSONArray(contact.attachedFilesJson)
                                                    for (i in 0 until arr.length()) { currentList.add(arr.getString(i)) }
                                                } catch (e: Exception) {}
                                            }
                                            currentList.add(copiedFile.absolutePath)
                                            val updated = contact.copy(attachedFilesJson = org.json.JSONArray(currentList).toString())
                                            viewModel.updateContact(updated)
                                            selectedContact = updated
                                            Toast.makeText(context, "Document attached successfully!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(18.dp))
                                        Text("Files and Photos", color = WaterBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        IconButton(
                                            onClick = { photoPickerLauncher.launch("image/*") },
                                            modifier = Modifier.size(28.dp).clip(CircleShape).background(WaterBlue.copy(alpha = 0.15f))
                                        ) {
                                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Add Photo", tint = WaterBlue, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { docPickerLauncher.launch("*/*") },
                                            modifier = Modifier.size(28.dp).clip(CircleShape).background(WaterBlue.copy(alpha = 0.15f))
                                        ) {
                                            Icon(Icons.Default.AttachFile, contentDescription = "Add Document", tint = WaterBlue, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            if (attachedFiles.isEmpty()) {
                                item {
                                    Text("No files or photos uploaded.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp))
                                }
                            } else {
                                itemsIndexed(attachedFiles, key = { idx, filePath -> "attached_${filePath}_$idx" }) { _, filePath ->
                                    val file = File(filePath)
                                    val isUrl = filePath.startsWith("http://") || filePath.startsWith("https://")
                                    val isContentUri = filePath.startsWith("content://")
                                    val isProfilePic = (filePath == contact.photoUri)
                                    val displayName = when {
                                        isProfilePic -> "Profile Photo"
                                        isUrl -> "Online Photo"
                                        else -> file.name.substringAfter("photo_").substringAfter("doc_").substringAfter("_")
                                    }
                                    val isPhoto = isPhotoPath(filePath) || isProfilePic || (!isUrl && !isContentUri && isPhotoFile(file))
                                    
                                    var showOptionsDialog by remember { mutableStateOf(false) }

                                    if (showOptionsDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showOptionsDialog = false },
                                            title = { Text(if (isProfilePic) "Profile Photo Options" else "Attachment Options", color = Color.White) },
                                            text = { Text("Choose action for '$displayName':", color = Color.LightGray) },
                                            confirmButton = {
                                                Button(
                                                    onClick = {
                                                        showOptionsDialog = false
                                                        val success = saveFileOrUrlToDownloads(context, filePath, displayName)
                                                        if (success) {
                                                            Toast.makeText(context, "Saved to Downloads folder!", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "Save completed or in storage", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                                                ) {
                                                    Text("Save to Device")
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(
                                                    onClick = {
                                                        showOptionsDialog = false
                                                        val newList = attachedFiles.filter { it != filePath }
                                                        val updated = if (isProfilePic) {
                                                            contact.copy(photoUri = null, attachedFilesJson = org.json.JSONArray(newList).toString())
                                                        } else {
                                                            contact.copy(attachedFilesJson = org.json.JSONArray(newList).toString())
                                                        }
                                                        viewModel.updateContact(updated)
                                                        selectedContact = updated
                                                        if (!isUrl && !isContentUri && file.exists()) {
                                                            try { file.delete() } catch (_: Exception) {}
                                                        }
                                                        Toast.makeText(context, "Attachment removed!", Toast.LENGTH_SHORT).show()
                                                    }
                                                ) {
                                                    Text("Delete", color = Color.Red)
                                                }
                                            },
                                            containerColor = SurfaceCard
                                        )
                                    }

                                    val ext = if (!isUrl && !isContentUri) file.extension.lowercase() else ""
                                    val isVideo = ext == "mp4" || ext == "mov" || ext == "3gp" || ext == "mkv"
                                    val isPdf = ext == "pdf"

                                    if (isPhoto || isVideo || isPdf) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 5.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .border(
                                                    width = if (isProfilePic) 1.5.dp else 1.dp,
                                                    color = if (isProfilePic) WaterBlue.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .combinedClickable(
                                                    onClick = {
                                                        try {
                                                            if (isUrl) {
                                                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(filePath))
                                                                context.startActivity(browserIntent)
                                                            } else if (isContentUri) {
                                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                    setDataAndType(Uri.parse(filePath), "image/*")
                                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                }
                                                                context.startActivity(Intent.createChooser(intent, "Open Photo"))
                                                            } else if (file.exists()) {
                                                                val authority = "${context.packageName}.fileprovider"
                                                                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                    val mimeType = if (isPhoto) "image/*" else if (isVideo) "video/*" else "application/pdf"
                                                                    setDataAndType(uri, mimeType)
                                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                }
                                                                context.startActivity(Intent.createChooser(intent, "Open File"))
                                                            } else {
                                                                showOptionsDialog = true
                                                            }
                                                        } catch (e: Exception) {
                                                            showOptionsDialog = true
                                                        }
                                                    },
                                                    onLongClick = {
                                                        showOptionsDialog = true
                                                    }
                                                )
                                        ) {
                                            Column {
                                                if (isPhoto) {
                                                    val imageModel: Any = remember(filePath) {
                                                        when {
                                                            isUrl -> filePath
                                                            isContentUri -> Uri.parse(filePath)
                                                            else -> if (file.exists()) file else filePath
                                                        }
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(200.dp)
                                                            .background(Color.Black.copy(alpha = 0.35f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        AsyncImage(
                                                            model = ImageRequest.Builder(LocalContext.current)
                                                                .data(imageModel)
                                                                .crossfade(true)
                                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                                .build(),
                                                            contentDescription = displayName,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                        if (isProfilePic) {
                                                            Surface(
                                                                modifier = Modifier
                                                                    .align(Alignment.TopStart)
                                                                    .padding(8.dp),
                                                                color = WaterBlue,
                                                                shape = RoundedCornerShape(6.dp)
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.AccountCircle,
                                                                        contentDescription = null,
                                                                        tint = Color.Black,
                                                                        modifier = Modifier.size(13.dp)
                                                                    )
                                                                    Text(
                                                                        text = "PROFILE PIC",
                                                                        color = Color.Black,
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else if (isVideo) {
                                                    val thumbnailBitmap = rememberVideoThumbnail(file.absolutePath)
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(180.dp)
                                                            .background(Color.Black),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (thumbnailBitmap != null) {
                                                            Image(
                                                                bitmap = thumbnailBitmap,
                                                                contentDescription = "Video Thumbnail Preview",
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.fillMaxSize()
                                                            )
                                                        }
                                                        Box(
                                                            modifier = Modifier
                                                                .size(48.dp)
                                                                .clip(CircleShape)
                                                                .background(Color.Black.copy(alpha = 0.5f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.PlayArrow,
                                                                contentDescription = "Play",
                                                                tint = Color.White,
                                                                modifier = Modifier.size(32.dp)
                                                            )
                                                        }
                                                    }
                                                } else if (isPdf) {
                                                    val pdfBitmap = rememberPdfFirstPagePreview(file.absolutePath)
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(180.dp)
                                                            .background(Color(0xFF1C1B1F)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (pdfBitmap != null) {
                                                            Image(
                                                                bitmap = pdfBitmap,
                                                                contentDescription = "PDF Preview",
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.fillMaxSize()
                                                            )
                                                        } else {
                                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                                Icon(
                                                                    imageVector = Icons.Default.InsertDriveFile,
                                                                    contentDescription = "PDF",
                                                                    tint = Color(0xFFE57373),
                                                                    modifier = Modifier.size(48.dp)
                                                                )
                                                                Spacer(modifier = Modifier.height(4.dp))
                                                                Text("PDF Document", color = Color.LightGray, fontSize = 11.sp)
                                                            }
                                                        }
                                                    }
                                                }

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        val infoText = when {
                                                            isProfilePic -> "Default Contact Profile Photo • Active"
                                                            isUrl -> "Cloud Image • Hold for options"
                                                            file.exists() -> "${file.length() / 1024} KB • Hold for options"
                                                            else -> "Image attachment • Hold for options"
                                                        }
                                                        Text(infoText, color = Color.Gray, fontSize = 9.sp)
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            val newList = attachedFiles.filter { it != filePath }
                                                            val updated = if (isProfilePic) {
                                                                contact.copy(photoUri = null, attachedFilesJson = org.json.JSONArray(newList).toString())
                                                            } else {
                                                                contact.copy(attachedFilesJson = org.json.JSONArray(newList).toString())
                                                            }
                                                            viewModel.updateContact(updated)
                                                            selectedContact = updated
                                                            if (!isUrl && !isContentUri && file.exists()) {
                                                                try { file.delete() } catch (_: Exception) {}
                                                            }
                                                            Toast.makeText(context, "Attachment removed!", Toast.LENGTH_SHORT).show()
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(Icons.Default.Close, contentDescription = "Delete Attachment", tint = Color.Red, modifier = Modifier.size(14.dp))
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .combinedClickable(
                                                    onClick = {
                                                        try {
                                                            if (file.exists()) {
                                                                val authority = "${context.packageName}.fileprovider"
                                                                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                }
                                                                context.startActivity(Intent.createChooser(intent, "Open File"))
                                                            } else {
                                                                showOptionsDialog = true
                                                            }
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Open failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onLongClick = {
                                                        showOptionsDialog = true
                                                    }
                                                )
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.FileOpen, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${if (file.exists()) file.length() / 1024 else 0} KB • Hold for options", color = Color.Gray, fontSize = 9.sp)
                                            }
                                            IconButton(
                                                onClick = {
                                                    val newList = attachedFiles.filter { it != filePath }
                                                    val updated = contact.copy(attachedFilesJson = org.json.JSONArray(newList).toString())
                                                    viewModel.updateContact(updated)
                                                    selectedContact = updated
                                                    if (file.exists()) {
                                                        try { file.delete() } catch (_: Exception) {}
                                                    }
                                                    Toast.makeText(context, "Attachment removed!", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Delete Attachment", tint = Color.Red, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

            Box(modifier = modifier.fillMaxSize()) {
                if (!isTablet) {
                    if (selectedContact == null) {
                        contactListUI()
                    } else {
                        contactDetailsUI(selectedContact!!)
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                            contactListUI()
                        }
                        Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                            if (selectedContact != null) {
                                contactDetailsUI(selectedContact!!)
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth().padding(32.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(32.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AccountBox,
                                                contentDescription = null,
                                                tint = WaterBlue.copy(alpha = 0.4f),
                                                modifier = Modifier.size(64.dp)
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = "Select a Contact",
                                                color = Color.White,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Choose a contact from the list to view their details, files, and timeline.",
                                                color = Color.Gray,
                                                fontSize = 12.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            // Scrim background when folders sidebar is open to dismiss on clicking outside
            androidx.compose.animation.AnimatedVisibility(
                visible = isFoldersSidebarExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            isFoldersSidebarExpanded = false
                        }
                )
            }

            // Floating Folders Sidebar Overlay (Renders on top aligned to Left)
            androidx.compose.animation.AnimatedVisibility(
                visible = isFoldersSidebarExpanded,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp))
                        .border(1.dp, Color(0xFF2E2E30), RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "FOLDERS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = WaterBlue,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )

                        // Default "All" Folder Row (unremovable & uneditable)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedFolder == "All") WaterBlue.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    selectedFolder = "All"
                                    isFoldersSidebarExpanded = false
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "All Contacts",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // User Created Folders list
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(foldersList, key = { idx, folder -> "folder_${folder}_$idx" }) { _, folder ->
                                val isSelected = selectedFolder == folder
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) WaterBlue.copy(alpha = 0.15f) else Color.Transparent)
                                        .combinedClickable(
                                            onClick = {
                                                selectedFolder = folder
                                                isFoldersSidebarExpanded = false
                                            },
                                            onLongClick = {
                                                renameFolderOldName = folder
                                                renameFolderNewName = folder
                                                folderToDelete = folder
                                                showRenameFolderDialog = true
                                                isFoldersSidebarExpanded = false
                                            }
                                        )
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Create Folder Button inside Sidebar
                        Button(
                            onClick = {
                                newFolderName = ""
                                showCreateFolderDialog = true
                                isFoldersSidebarExpanded = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Folder", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Folder", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } // End of outer Box
    }

        ContactScreen.ADD, ContactScreen.EDIT -> {
            // Screen Title
            val isEdit = screenState == ContactScreen.EDIT
            
            Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
                // Form Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { handleBackPress() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = WaterBlue)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEdit) "Edit Contact Details" else "New Premium Contact",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = {
                            if (firstName.trim().isNotEmpty()) {
                                val datesJson = customDatesList.joinToString(";") { "${it.first}:${it.second}" }
                                val fieldsJson = customFieldsList.joinToString(";") { "${it.first}:${it.second}" }
                                
                                val photoToSave = selectedAvatar.takeIf { it.isNotEmpty() }
                                if (isEdit && selectedContact != null) {
                                    val currentAttached = mutableListOf<String>()
                                    if (selectedContact!!.attachedFilesJson.isNotEmpty()) {
                                        try {
                                            val arr = org.json.JSONArray(selectedContact!!.attachedFilesJson)
                                            for (i in 0 until arr.length()) {
                                                val itm = arr.getString(i)
                                                if (itm.isNotBlank() && !currentAttached.contains(itm)) currentAttached.add(itm)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    if (!photoToSave.isNullOrEmpty() && !currentAttached.contains(photoToSave)) {
                                        currentAttached.add(0, photoToSave)
                                    }
                                    val updated = selectedContact!!.copy(
                                        firstName = firstName.trim(),
                                        middleName = middleName.trim(),
                                        lastName = lastName.trim(),
                                        jobTitle = jobTitle.trim(),
                                        email = email.trim(),
                                        address = address.trim(),
                                        phone = phone.trim(),
                                        dobString = dobString.trim(),
                                        photoUri = photoToSave,
                                        anniversaryString = anniversaryString.trim(),
                                        additionalFieldsJson = fieldsJson,
                                        additionalDatesJson = datesJson,
                                        folder = selectedFolderOption,
                                        attachedFilesJson = org.json.JSONArray(currentAttached).toString()
                                    )
                                    viewModel.updateContact(updated)
                                    selectedContact = updated
                                    Toast.makeText(context, "Contact details updated!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val currentAttached = mutableListOf<String>()
                                    if (!photoToSave.isNullOrEmpty()) {
                                        currentAttached.add(photoToSave)
                                    }
                                    viewModel.createContact(
                                        firstName = firstName.trim(),
                                        middleName = middleName.trim(),
                                        lastName = lastName.trim(),
                                        jobTitle = jobTitle.trim(),
                                        email = email.trim(),
                                        address = address.trim(),
                                        phone = phone.trim(),
                                        dobString = dobString.trim(),
                                        photoUri = photoToSave,
                                        anniversaryString = anniversaryString.trim(),
                                        additionalFieldsJson = fieldsJson,
                                        additionalDatesJson = datesJson,
                                        folder = selectedFolderOption,
                                        attachedFilesJson = org.json.JSONArray(currentAttached).toString()
                                    )
                                    Toast.makeText(context, "Contact created in folder: $selectedFolderOption", Toast.LENGTH_SHORT).show()
                                }
                                screenState = ContactScreen.LIST
                            } else {
                                Toast.makeText(context, "First name is mandatory", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Details", fontWeight = FontWeight.Bold)
                    }
                }

                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Profile image configure column (Left inside Form)
                    Column(
                        modifier = Modifier.width(200.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("PROFILE PICTURE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WaterBlue)
                        
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(Charcoal)
                                .border(2.dp, WaterBlue, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedAvatar.isNotEmpty()) {
                                val imageModel = remember(selectedAvatar) {
                                    if (selectedAvatar.startsWith("/")) File(selectedAvatar) else selectedAvatar
                                }
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(imageModel)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Profile Pic Selector",
                                    modifier = Modifier.clip(CircleShape).fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                            }
                        }

                        Button(
                            onClick = { galleryPickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f), contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("From Device", fontSize = 11.sp)
                        }

                        // Presets Row
                        Text("Select Preset Avatar:", fontSize = 10.sp, color = Color.Gray)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        ) {
                            itemsIndexed(AVATAR_OPTIONS, key = { idx, url -> "avatar_opt_${url}_$idx" }) { _, url ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (selectedAvatar == url) WaterBlue.copy(alpha = 0.3f) else Color.Transparent)
                                        .border(
                                            width = if (selectedAvatar == url) 2.dp else 1.dp,
                                            color = if (selectedAvatar == url) WaterBlue else Color.Gray.copy(alpha = 0.3f),
                                            shape = CircleShape
                                        )
                                        .clickable { selectedAvatar = url }
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.clip(CircleShape).fillMaxSize())
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Folder selector dropdown/radio options
                        Text("ASSOCIATED FOLDER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WaterBlue)
                        
                        var showFolderDropdown by remember { mutableStateOf(false) }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Charcoal)
                                .clickable { showFolderDropdown = true }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedFolderOption, color = Color.White, fontSize = 13.sp)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = WaterBlue)
                            }

                            DropdownMenu(
                                expanded = showFolderDropdown,
                                onDismissRequest = { showFolderDropdown = false },
                                modifier = Modifier.background(SurfaceCard)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All", color = Color.White) },
                                    onClick = {
                                        selectedFolderOption = "All"
                                        showFolderDropdown = false
                                    }
                                )
                                foldersList.forEach { folder ->
                                    DropdownMenuItem(
                                        text = { Text(folder, color = Color.White) },
                                        onClick = {
                                            selectedFolderOption = folder
                                            showFolderDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Fields configure column
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            TextField(
                                value = firstName,
                                onValueChange = { firstName = it },
                                label = { Text("First Name *") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("contact_first_name")
                            )
                        }
                        item {
                            TextField(
                                value = middleName,
                                onValueChange = { middleName = it },
                                label = { Text("Middle Name") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            TextField(
                                value = lastName,
                                onValueChange = { lastName = it },
                                label = { Text("Last Name") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            TextField(
                                value = jobTitle,
                                onValueChange = { jobTitle = it },
                                label = { Text("Job Title") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            TextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("Phone Number") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            TextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Email Address") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            TextField(
                                value = address,
                                onValueChange = { address = it },
                                label = { Text("Street Address") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            TextField(
                                value = dobString,
                                onValueChange = { dobString = formatAutoDate(it, dobString) },
                                label = { Text("Date of Birth (DD/MM/YYYY)") },
                                placeholder = { Text("e.g. 20/06/1998") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            TextField(
                                value = anniversaryString,
                                onValueChange = { anniversaryString = formatAutoDate(it, anniversaryString) },
                                label = { Text("Anniversary (DD/MM/YYYY)") },
                                placeholder = { Text("e.g. 15/08/2012") },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                    focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Custom Dates
                        item {
                            Divider(color = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                            Text("Custom Dates to Remember", color = WaterBlue, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        itemsIndexed(customDatesList.toList(), key = { idx, pair -> "custom_date_${pair.first}_${pair.second}_$idx" }) { _, pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${pair.first}: ${pair.second}", color = Color.White, fontSize = 12.sp)
                                IconButton(onClick = { customDatesList.remove(pair) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = customDateNameDraft,
                                    onValueChange = { customDateNameDraft = it },
                                    label = { Text("Label") },
                                    colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                                    modifier = Modifier.weight(1f)
                                )
                                TextField(
                                    value = customDateValueDraft,
                                    onValueChange = { customDateValueDraft = formatAutoDate(it, customDateValueDraft) },
                                    label = { Text("DD/MM/YYYY") },
                                    colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                                    modifier = Modifier.weight(1.2f)
                                )
                                IconButton(onClick = {
                                    if (customDateNameDraft.isNotBlank() && customDateValueDraft.isNotBlank()) {
                                        customDatesList.add(customDateNameDraft.trim() to customDateValueDraft.trim())
                                        customDateNameDraft = ""
                                        customDateValueDraft = ""
                                    }
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Custom Date", tint = WaterBlue)
                                }
                            }
                        }

                        // Custom Fields
                        item {
                            Divider(color = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                            Text("Custom Fields (Instagram, Snapchat, Links, etc.)", color = WaterBlue, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            
                            // Quick Add Chips for Instagram & Snapchat
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "Instagram ID" to "📸 +Insta ID",
                                    "Instagram Profile Link" to "📸 +Insta Link",
                                    "Snapchat Profile Link" to "👻 +Snap Link",
                                    "Snapchat ID" to "👻 +Snap ID"
                                ).forEach { (fieldName, label) ->
                                    SuggestionChip(
                                        onClick = {
                                            customFieldNameDraft = fieldName
                                        },
                                        label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = Color.White.copy(alpha = 0.08f),
                                            labelColor = WaterBlue
                                        ),
                                        border = SuggestionChipDefaults.suggestionChipBorder(
                                            enabled = true,
                                            borderColor = WaterBlue.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            }
                        }
                        itemsIndexed(customFieldsList.toList(), key = { idx, pair -> "custom_field_${pair.first}_${pair.second}_$idx" }) { _, pair ->
                            val classified = remember(pair) {
                                com.example.util.ContactSocialHelper.classifyField(pair.first, pair.second)
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = when (classified.type) {
                                        com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_ID,
                                        com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_LINK -> Color(0xFFE1306C).copy(alpha = 0.12f)
                                        com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_ID,
                                        com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_LINK -> Color(0xFFFFFC00).copy(alpha = 0.1f)
                                        else -> Color.White.copy(alpha = 0.04f)
                                    }
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val typeLabel = when (classified.type) {
                                            com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_ID -> "📸 Instagram ID"
                                            com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_LINK -> "📸 Instagram Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_ID -> "👻 Snapchat ID"
                                            com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_LINK -> "👻 Snapchat Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.TWITTER_ID -> "🐦 Twitter / X Handle"
                                            com.example.util.ContactSocialHelper.CustomFieldType.TWITTER_LINK -> "🐦 Twitter / X Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.TELEGRAM_ID -> "✈️ Telegram Handle"
                                            com.example.util.ContactSocialHelper.CustomFieldType.TELEGRAM_LINK -> "✈️ Telegram Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.FACEBOOK_ID -> "👥 Facebook Profile"
                                            com.example.util.ContactSocialHelper.CustomFieldType.FACEBOOK_LINK -> "👥 Facebook Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.LINKEDIN_ID -> "💼 LinkedIn Profile"
                                            com.example.util.ContactSocialHelper.CustomFieldType.LINKEDIN_LINK -> "💼 LinkedIn Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.YOUTUBE_ID -> "▶️ YouTube Channel"
                                            com.example.util.ContactSocialHelper.CustomFieldType.YOUTUBE_LINK -> "▶️ YouTube Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.GENERAL_LINK -> "🔗 Web Link"
                                            com.example.util.ContactSocialHelper.CustomFieldType.TEXT -> "📝 Custom Field"
                                        }
                                        Text(
                                            text = "${pair.first} ($typeLabel)",
                                            color = when (classified.type) {
                                                com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_ID,
                                                com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_LINK -> Color(0xFFFF80AB)
                                                com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_ID,
                                                com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_LINK -> Color(0xFFFFFC00)
                                                com.example.util.ContactSocialHelper.CustomFieldType.TWITTER_ID,
                                                com.example.util.ContactSocialHelper.CustomFieldType.TWITTER_LINK -> Color(0xFF1DA1F2)
                                                com.example.util.ContactSocialHelper.CustomFieldType.TELEGRAM_ID,
                                                com.example.util.ContactSocialHelper.CustomFieldType.TELEGRAM_LINK -> Color(0xFF0088CC)
                                                com.example.util.ContactSocialHelper.CustomFieldType.FACEBOOK_ID,
                                                com.example.util.ContactSocialHelper.CustomFieldType.FACEBOOK_LINK -> Color(0xFF1877F2)
                                                com.example.util.ContactSocialHelper.CustomFieldType.LINKEDIN_ID,
                                                com.example.util.ContactSocialHelper.CustomFieldType.LINKEDIN_LINK -> Color(0xFF0A66C2)
                                                com.example.util.ContactSocialHelper.CustomFieldType.YOUTUBE_ID,
                                                com.example.util.ContactSocialHelper.CustomFieldType.YOUTUBE_LINK -> Color(0xFFFF5252)
                                                else -> WaterBlue
                                            },
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(pair.second, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { customFieldsList.remove(pair) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = customFieldNameDraft,
                                    onValueChange = { customFieldNameDraft = it },
                                    label = { Text("Field Name") },
                                    colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                                    modifier = Modifier.weight(1f)
                                )
                                TextField(
                                    value = customFieldValueDraft,
                                    onValueChange = { customFieldValueDraft = it },
                                    label = { Text("Value") },
                                    colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                                    modifier = Modifier.weight(1.2f)
                                )
                                IconButton(onClick = {
                                    if (customFieldNameDraft.isNotBlank() && customFieldValueDraft.isNotBlank()) {
                                        customFieldsList.add(customFieldNameDraft.trim() to customFieldValueDraft.trim())
                                        customFieldNameDraft = ""
                                        customFieldValueDraft = ""
                                    }
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Custom Field", tint = WaterBlue)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DIALOGS FOR FOLDER ACTIONS ---

    // 1. Create Folder
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder", color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                TextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.trim().isNotEmpty()) {
                            viewModel.createContactFolder(newFolderName.trim())
                            showCreateFolderDialog = false
                        } else {
                            Toast.makeText(context, "Folder name cannot be blank", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    // 2. Rename & Delete Folder Selector Dialog (triggered on folder long press)
    if (showRenameFolderDialog) {
        AlertDialog(
            onDismissRequest = { showRenameFolderDialog = false },
            title = { Text("Folder Options: $renameFolderOldName", color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Rename Folder:", color = Color.Gray, fontSize = 12.sp)
                    TextField(
                        value = renameFolderNewName,
                        onValueChange = { renameFolderNewName = it },
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray, focusedContainerColor = SurfaceCard, unfocusedContainerColor = SurfaceCard),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Danger Zone:", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            showRenameFolderDialog = false
                            showDeleteFolderDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete Folder and reset contacts to All", fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameFolderNewName.trim().isNotEmpty() && renameFolderNewName != renameFolderOldName) {
                            viewModel.renameContactFolder(renameFolderOldName, renameFolderNewName.trim())
                            if (selectedFolder == renameFolderOldName) {
                                selectedFolder = renameFolderNewName.trim()
                            }
                            showRenameFolderDialog = false
                            Toast.makeText(context, "Folder renamed to: $renameFolderNewName", Toast.LENGTH_SHORT).show()
                        } else {
                            showRenameFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Text("Rename", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFolderDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    // 3. Delete Folder Confirmation
    if (showDeleteFolderDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteFolderDialog = false },
            title = { Text("Delete Folder?", color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Text(
                    "Are you sure you want to delete folder '$folderToDelete'? Contacts inside this folder will stay preserved, resetting back to the 'All' folder.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteContactFolder(folderToDelete)
                        if (selectedFolder == folderToDelete) {
                            selectedFolder = "All"
                        }
                        showDeleteFolderDialog = false
                        Toast.makeText(context, "Folder deleted successfully", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                ) {
                    Text("Delete Folder", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFolderDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    // --- DIALOGS FOR CONTACT ACTIONS (LONG PRESS TARGETS) ---

    // 1. Long Press Main Actions
    if (showContactActionDialog && longPressedContact != null) {
        AlertDialog(
            onDismissRequest = { showContactActionDialog = false },
            title = {
                Text(
                    text = "Manage ${longPressedContact?.firstName} ${longPressedContact?.lastName}",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
            },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            showContactActionDialog = false
                            // Load form entries to go to edit screen
                            val c = longPressedContact!!
                            firstName = c.firstName
                            middleName = c.middleName
                            lastName = c.lastName
                            jobTitle = c.jobTitle
                            email = c.email
                            address = c.address
                            phone = c.phone
                            dobString = c.dobString
                            anniversaryString = c.anniversaryString
                            selectedAvatar = c.photoUri ?: ""
                            selectedFolderOption = c.folder

                            customFieldsList.clear()
                            if (c.additionalFieldsJson.isNotEmpty()) {
                                val parsed = com.example.util.ContactSocialHelper.parseCustomFields(c.additionalFieldsJson)
                                customFieldsList.addAll(parsed)
                            }

                            customDatesList.clear()
                            if (c.additionalDatesJson.isNotEmpty()) {
                                c.additionalDatesJson.split(";").forEach { pair ->
                                    val parts = pair.split(":")
                                    if (parts.size == 2) customDatesList.add(parts[0] to parts[1])
                                }
                            }

                            screenState = ContactScreen.EDIT
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Edit Contact", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            showContactActionDialog = false
                            showConfirmDeleteContactDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Contact", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showContactActionDialog = false }) {
                    Text("Close", color = Color.White)
                }
            }
        )
    }

    // 2. Confirm Delete Contact
    if (showConfirmDeleteContactDialog && longPressedContact != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDeleteContactDialog = false },
            title = { Text("Delete Contact?", color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
            text = {
                Text(
                    "This action is permanent. Are you sure you want to securely delete ${longPressedContact?.firstName} ${longPressedContact?.lastName} from your address book?",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val c = longPressedContact!!
                        viewModel.deleteContact(c)
                        if (selectedContact?.id == c.id) {
                            selectedContact = null
                        }
                        showConfirmDeleteContactDialog = false
                        Toast.makeText(context, "Contact deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                ) {
                    Text("Delete Details", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteContactDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

@Composable
fun ContactDetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
fun ContactCustomFieldRow(key: String, value: String, phoneNumber: String? = null) {
    val recognized = remember(key, value) {
        com.example.util.ContactSocialHelper.classifyField(key, value)
    }
    ContactCustomFieldRow(recognized = recognized, phoneNumber = phoneNumber)
}

@Composable
fun ContactCustomFieldRow(
    recognized: com.example.util.ContactSocialHelper.RecognizedField,
    phoneNumber: String? = null
) {
    val context = LocalContext.current
    val key = recognized.key
    val value = recognized.value

    when (recognized.type) {
        com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_ID,
        com.example.util.ContactSocialHelper.CustomFieldType.INSTAGRAM_LINK -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!phoneNumber.isNullOrBlank()) {
                    WhatsAppRedirectionCard(phoneNumber = phoneNumber)
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            com.example.util.ContactSocialHelper.openInstagram(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE1306C).copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1306C).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_instagram_shortcut),
                            contentDescription = "Instagram",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val headerLabel = if (key.equals("Instagram", ignoreCase = true)) "INSTAGRAM" else "INSTAGRAM ($key)"
                            Text(
                                text = headerLabel,
                                color = Color(0xFFFF80AB),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = recognized.displayHandle,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = {
                                com.example.util.ContactSocialHelper.openInstagram(
                                    context,
                                    recognized.actionUrl ?: value
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE1306C),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_ID,
        com.example.util.ContactSocialHelper.CustomFieldType.SNAPCHAT_LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.example.util.ContactSocialHelper.openSnapchat(
                            context,
                            recognized.actionUrl ?: value
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFC00).copy(alpha = 0.12f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFFC00).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_snapchat_logo),
                        contentDescription = "Snapchat",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val headerLabel = if (key.equals("Snapchat", ignoreCase = true)) "SNAPCHAT" else "SNAPCHAT ($key)"
                        Text(
                            text = headerLabel,
                            color = Color(0xFFFFFC00),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recognized.displayHandle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            com.example.util.ContactSocialHelper.openSnapchat(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFFC00),
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.TWITTER_ID,
        com.example.util.ContactSocialHelper.CustomFieldType.TWITTER_LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.example.util.ContactSocialHelper.openWebUrl(
                            context,
                            recognized.actionUrl ?: value
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1DA1F2).copy(alpha = 0.12f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1DA1F2).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AlternateEmail,
                        contentDescription = "Twitter / X",
                        tint = Color(0xFF1DA1F2),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TWITTER / X",
                            color = Color(0xFF1DA1F2),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recognized.displayHandle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            com.example.util.ContactSocialHelper.openWebUrl(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1DA1F2),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.TELEGRAM_ID,
        com.example.util.ContactSocialHelper.CustomFieldType.TELEGRAM_LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.example.util.ContactSocialHelper.openWebUrl(
                            context,
                            recognized.actionUrl ?: value
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0088CC).copy(alpha = 0.12f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0088CC).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Telegram",
                        tint = Color(0xFF0088CC),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TELEGRAM",
                            color = Color(0xFF0088CC),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recognized.displayHandle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            com.example.util.ContactSocialHelper.openWebUrl(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0088CC),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.FACEBOOK_ID,
        com.example.util.ContactSocialHelper.CustomFieldType.FACEBOOK_LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.example.util.ContactSocialHelper.openWebUrl(
                            context,
                            recognized.actionUrl ?: value
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1877F2).copy(alpha = 0.12f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1877F2).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Facebook",
                        tint = Color(0xFF1877F2),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FACEBOOK",
                            color = Color(0xFF1877F2),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recognized.displayHandle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            com.example.util.ContactSocialHelper.openWebUrl(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1877F2),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.LINKEDIN_ID,
        com.example.util.ContactSocialHelper.CustomFieldType.LINKEDIN_LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.example.util.ContactSocialHelper.openWebUrl(
                            context,
                            recognized.actionUrl ?: value
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A66C2).copy(alpha = 0.12f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0A66C2).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Work,
                        contentDescription = "LinkedIn",
                        tint = Color(0xFF0A66C2),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LINKEDIN",
                            color = Color(0xFF0A66C2),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recognized.displayHandle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            com.example.util.ContactSocialHelper.openWebUrl(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0A66C2),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.YOUTUBE_ID,
        com.example.util.ContactSocialHelper.CustomFieldType.YOUTUBE_LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.example.util.ContactSocialHelper.openWebUrl(
                            context,
                            recognized.actionUrl ?: value
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF0000).copy(alpha = 0.12f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_youtube_shortcut),
                        contentDescription = "YouTube",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "YOUTUBE",
                            color = Color(0xFFFF5252),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = recognized.displayHandle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            com.example.util.ContactSocialHelper.openWebUrl(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF0000),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Watch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.GENERAL_LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        com.example.util.ContactSocialHelper.openWebUrl(
                            context,
                            recognized.actionUrl ?: value
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = WaterBlue.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, WaterBlue.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Link",
                        tint = WaterBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = key.uppercase(),
                            color = WaterBlue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = value,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            com.example.util.ContactSocialHelper.openWebUrl(
                                context,
                                recognized.actionUrl ?: value
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WaterBlue,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Visit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        com.example.util.ContactSocialHelper.CustomFieldType.TEXT -> {
            ContactDetailRow(Icons.Default.Info, key, value)
        }
    }
}

// Helper functions for file management
private fun getFileName(context: android.content.Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "unnamed_document"
}

private fun copyUriToLocalFile(context: android.content.Context, uri: Uri, destFileName: String): File? {
    return com.example.util.StorageHelper.copyFileToInternalSandbox(context, uri)
}

private fun isPhotoPath(filePath: String): Boolean {
    val lower = filePath.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("content://") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.contains("avatar") ||
            lower.contains("photo") || lower.contains("profile")
}

private fun isPhotoFile(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" || ext == "gif"
}

private fun saveFileOrUrlToDownloads(context: android.content.Context, filePath: String, displayName: String): Boolean {
    return try {
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            val mgr = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
            if (mgr != null) {
                val safeFileName = if (displayName.contains(".")) displayName else "$displayName.jpg"
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(filePath))
                    .setTitle(displayName)
                    .setDescription("Downloading photo")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, safeFileName)
                mgr.enqueue(request)
                true
            } else {
                false
            }
        } else {
            val file = File(filePath)
            if (file.exists()) {
                saveFileToDownloads(context, file, displayName)
            } else {
                false
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun saveFileToDownloads(context: android.content.Context, sourceFile: File, displayName: String): Boolean {
    return try {
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "3gp" -> "video/3gp"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val downloadsUri = android.net.Uri.parse("content://media/external/downloads")
            resolver.insert(downloadsUri, contentValues)
        } else {
            null
        }
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            true
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val destFile = File(downloadsDir, displayName)
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

@Composable
fun WhatsAppRedirectionCard(phoneNumber: String) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable {
                com.example.util.ContactSocialHelper.openWhatsApp(context, phoneNumber)
            },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF25D366).copy(alpha = 0.14f)),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF25D366).copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF25D366)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = "WhatsApp",
                    tint = Color.Black,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WHATSAPP REDIRECTION (wa.me)",
                    color = Color(0xFF25D366),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = phoneNumber,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = {
                    com.example.util.ContactSocialHelper.openWhatsApp(context, phoneNumber)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF25D366),
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Chat on WA", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

