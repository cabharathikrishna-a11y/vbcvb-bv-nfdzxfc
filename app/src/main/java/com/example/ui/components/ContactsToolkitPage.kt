package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Contact
import com.example.ui.AppViewModel
import com.example.util.ContactDuplicateGroup
import com.example.util.ContactDuplicateReason
import com.example.util.ContactToolkitHelper

@Composable
fun ContactsToolkitPage(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val duplicateGroups by viewModel.duplicateContacts.collectAsState()
    val manualConflicts = remember(duplicateGroups) {
        duplicateGroups.filter { !it.isExactMatch }
    }
    val exactMatches = remember(duplicateGroups) {
        duplicateGroups.filter { it.isExactMatch }
    }

    var resolvingGroup by remember { mutableStateOf<ContactDuplicateGroup?>(null) }
    var isAutoCleaning by remember { mutableStateOf(false) }

    SettingsPageScope {
        SettingsSubpageWorkspace(
            title = "Contacts Tool Kit",
            description = "Intelligent duplicate detection, conflict merging, and automatic cleanup.",
            onBack = onBack
        ) {
            // Header Action Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131318)),
                border = BorderStroke(1.dp, if (manualConflicts.isNotEmpty()) Color(0xFFE53935).copy(alpha = 0.5f) else Color(0xFF2E2E38))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (manualConflicts.isNotEmpty()) Color(0xFFE53935).copy(alpha = 0.15f) else WaterBlue.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (manualConflicts.isNotEmpty()) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (manualConflicts.isNotEmpty()) Color(0xFFE53935) else WaterBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (manualConflicts.isEmpty() && exactMatches.isEmpty()) "All Contacts Clean" else "${manualConflicts.size} Conflicting Contacts",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (manualConflicts.isEmpty() && exactMatches.isEmpty()) "No duplicated phone numbers, names, or Instagram IDs found" else "Same phone numbers, names, or Instagram IDs requiring resolution",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    if (exactMatches.isNotEmpty()) {
                        Button(
                            onClick = {
                                isAutoCleaning = true
                                viewModel.autoCleanExactDuplicates { count ->
                                    isAutoCleaning = false
                                    Toast.makeText(context, "Cleaned $count exact duplicate contacts!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isAutoCleaning,
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("auto_clean_exact_duplicates_btn"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isAutoCleaning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cleaning Exact Duplicates...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Auto-Clean ${exactMatches.size} Exact Duplicates", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // List of conflicts to resolve one-by-one
            if (manualConflicts.isEmpty() && exactMatches.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A1A22)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Clean",
                                tint = WaterBlue,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = "Zero Duplicates Detected",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Every contact in your phone book has unique phone, name, and social IDs.",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(manualConflicts, key = { idx, group -> "${group.id}_$idx" }) { _, group ->
                        ConflictCard(
                            group = group,
                            onResolve = { resolvingGroup = group },
                            onKeepPrimary = {
                                viewModel.deleteContact(group.duplicateContact)
                                Toast.makeText(context, "Kept \"${group.primaryContact.firstName}\" & deleted duplicate", Toast.LENGTH_SHORT).show()
                            },
                            onKeepDuplicate = {
                                viewModel.deleteContact(group.primaryContact)
                                Toast.makeText(context, "Kept \"${group.duplicateContact.firstName}\" & deleted primary", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // Modal Sheet or Dialog for One-By-One In-Depth Resolution
    resolvingGroup?.let { group ->
        ResolveConflictDialog(
            group = group,
            onDismiss = { resolvingGroup = null },
            onMerge = { merged ->
                viewModel.mergeContacts(group.primaryContact, group.duplicateContact, merged)
                resolvingGroup = null
                Toast.makeText(context, "Successfully merged contacts!", Toast.LENGTH_SHORT).show()
            },
            onKeepOnly = { kept, deleted ->
                viewModel.deleteContact(deleted)
                resolvingGroup = null
                Toast.makeText(context, "Kept ${kept.firstName} and deleted duplicate!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun ConflictCard(
    group: ContactDuplicateGroup,
    onResolve: () -> Unit,
    onKeepPrimary: () -> Unit,
    onKeepDuplicate: () -> Unit
) {
    val c1 = group.primaryContact
    val c2 = group.duplicateContact

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("conflict_card_${group.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F14)),
        border = BorderStroke(1.dp, Color(0xFF262633))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Match tag badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.matchReasons.forEach { reason ->
                        val (label, bg, fg) = when (reason) {
                            ContactDuplicateReason.SAME_PHONE -> Triple("Same Phone", Color(0xFF3F51B5).copy(alpha = 0.2f), Color(0xFF7986CB))
                            ContactDuplicateReason.SAME_NAME -> Triple("Same Name", Color(0xFFFF9800).copy(alpha = 0.2f), Color(0xFFFFB74D))
                            ContactDuplicateReason.SAME_INSTAGRAM -> Triple("Same Instagram", Color(0xFFE91E63).copy(alpha = 0.2f), Color(0xFFF06292))
                            ContactDuplicateReason.EXACT_MATCH -> Triple("Exact Duplicate", Color(0xFF4CAF50).copy(alpha = 0.2f), Color(0xFF81C784))
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(text = label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (!c1.googleContactId.isNullOrEmpty() || !c2.googleContactId.isNullOrEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = "Google Contacts", tint = WaterBlue, modifier = Modifier.size(14.dp))
                        Text("Google", color = WaterBlue, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Differences Summary
            if (group.conflictingFields.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF181822), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Differences detected:", color = Color.LightGray, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    group.conflictingFields.take(3).forEach { diff ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color(0xFFE53935)))
                            Text(diff, color = Color.White, fontSize = 10.5.sp)
                        }
                    }
                }
            }

            // Contacts Comparison Display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Primary Contact
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF14141E), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Contact #1",
                            color = WaterBlue,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!c1.googleContactId.isNullOrEmpty()) {
                            Text("Synced", color = Color(0xFF4CAF50), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${c1.firstName} ${c1.lastName}".ifBlank { "Untitled" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = c1.phone.ifBlank { "No phone" },
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    val ig1 = ContactToolkitHelper.extractInstagramHandle(c1.additionalFieldsJson)
                    if (ig1.isNotEmpty()) {
                        Text(
                            text = "@$ig1",
                            color = Color(0xFFF06292),
                            fontSize = 10.5.sp,
                            maxLines = 1
                        )
                    }
                }

                // Duplicate Contact
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF14141E), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Contact #2",
                            color = Color(0xFFFFB74D),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!c2.googleContactId.isNullOrEmpty()) {
                            Text("Synced", color = Color(0xFF4CAF50), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${c2.firstName} ${c2.lastName}".ifBlank { "Untitled" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = c2.phone.ifBlank { "No phone" },
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    val ig2 = ContactToolkitHelper.extractInstagramHandle(c2.additionalFieldsJson)
                    if (ig2.isNotEmpty()) {
                        Text(
                            text = "@$ig2",
                            color = Color(0xFFF06292),
                            fontSize = 10.5.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // Quick Resolution Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onKeepPrimary,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.8.dp, Color(0xFF333344))
                ) {
                    Text("Keep #1", color = Color.White, fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onKeepDuplicate,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.8.dp, Color(0xFF333344))
                ) {
                    Text("Keep #2", color = Color.White, fontSize = 11.sp)
                }

                Button(
                    onClick = onResolve,
                    modifier = Modifier.weight(1.2f).height(36.dp).testTag("resolve_group_${group.id}"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resolve", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ResolveConflictDialog(
    group: ContactDuplicateGroup,
    onDismiss: () -> Unit,
    onMerge: (Contact) -> Unit,
    onKeepOnly: (kept: Contact, deleted: Contact) -> Unit
) {
    val c1 = group.primaryContact
    val c2 = group.duplicateContact

    var selectedFirstName by remember { mutableStateOf(if (c1.firstName.isNotEmpty()) c1.firstName else c2.firstName) }
    var selectedLastName by remember { mutableStateOf(if (c1.lastName.isNotEmpty()) c1.lastName else c2.lastName) }
    var selectedPhone by remember { mutableStateOf(if (c1.phone.isNotEmpty()) c1.phone else c2.phone) }
    var selectedEmail by remember { mutableStateOf(if (c1.email.isNotEmpty()) c1.email else c2.email) }
    var selectedJob by remember { mutableStateOf(if (c1.jobTitle.isNotEmpty()) c1.jobTitle else c2.jobTitle) }
    var selectedAddress by remember { mutableStateOf(if (c1.address.isNotEmpty()) c1.address else c2.address) }
    var selectedDob by remember { mutableStateOf(if (c1.dobString.isNotEmpty()) c1.dobString else c2.dobString) }
    var selectedAnniversary by remember { mutableStateOf(if (c1.anniversaryString.isNotEmpty()) c1.anniversaryString else c2.anniversaryString) }

    val ig1 = ContactToolkitHelper.extractInstagramHandle(c1.additionalFieldsJson)
    val ig2 = ContactToolkitHelper.extractInstagramHandle(c2.additionalFieldsJson)
    var selectedInstagram by remember { mutableStateOf(if (ig1.isNotEmpty()) ig1 else ig2) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14141A),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.MergeType, contentDescription = null, tint = WaterBlue)
                Text("Resolve Contact Conflict", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "Tap the values you wish to keep in the unified contact profile, or keep only one contact completely.",
                        color = Color.LightGray,
                        fontSize = 11.5.sp
                    )
                }

                // Name field chooser
                item {
                    ConflictFieldRow(
                        label = "Full Name",
                        val1 = "${c1.firstName} ${c1.lastName}".trim(),
                        val2 = "${c2.firstName} ${c2.lastName}".trim(),
                        current = "$selectedFirstName $selectedLastName".trim(),
                        onSelect1 = {
                            selectedFirstName = c1.firstName
                            selectedLastName = c1.lastName
                        },
                        onSelect2 = {
                            selectedFirstName = c2.firstName
                            selectedLastName = c2.lastName
                        }
                    )
                }

                // Phone field chooser
                item {
                    ConflictFieldRow(
                        label = "Phone Number",
                        val1 = c1.phone,
                        val2 = c2.phone,
                        current = selectedPhone,
                        onSelect1 = { selectedPhone = c1.phone },
                        onSelect2 = { selectedPhone = c2.phone }
                    )
                }

                // Instagram field chooser (if present)
                if (ig1.isNotEmpty() || ig2.isNotEmpty()) {
                    item {
                        ConflictFieldRow(
                            label = "Instagram Handle",
                            val1 = if (ig1.isNotEmpty()) "@$ig1" else "None",
                            val2 = if (ig2.isNotEmpty()) "@$ig2" else "None",
                            current = if (selectedInstagram.isNotEmpty()) "@$selectedInstagram" else "None",
                            onSelect1 = { selectedInstagram = ig1 },
                            onSelect2 = { selectedInstagram = ig2 }
                        )
                    }
                }

                // Email field chooser
                if (c1.email.isNotEmpty() || c2.email.isNotEmpty()) {
                    item {
                        ConflictFieldRow(
                            label = "Email Address",
                            val1 = c1.email.ifBlank { "None" },
                            val2 = c2.email.ifBlank { "None" },
                            current = selectedEmail.ifBlank { "None" },
                            onSelect1 = { selectedEmail = c1.email },
                            onSelect2 = { selectedEmail = c2.email }
                        )
                    }
                }

                // Job Title chooser
                if (c1.jobTitle.isNotEmpty() || c2.jobTitle.isNotEmpty()) {
                    item {
                        ConflictFieldRow(
                            label = "Job Title",
                            val1 = c1.jobTitle.ifBlank { "None" },
                            val2 = c2.jobTitle.ifBlank { "None" },
                            current = selectedJob.ifBlank { "None" },
                            onSelect1 = { selectedJob = c1.jobTitle },
                            onSelect2 = { selectedJob = c2.jobTitle }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Assemble merged contact
                    // Choose additionalFields with preferred instagram handle if applicable
                    val mergedFieldsJson = if (selectedInstagram.isNotEmpty()) {
                        val baseJson = if (c1.additionalFieldsJson.contains("insta", ignoreCase = true)) c1.additionalFieldsJson else c2.additionalFieldsJson
                        if (baseJson.isBlank()) {
                            "[{\"first\":\"Instagram\",\"second\":\"$selectedInstagram\"}]"
                        } else {
                            baseJson
                        }
                    } else {
                        if (c1.additionalFieldsJson.isNotBlank()) c1.additionalFieldsJson else c2.additionalFieldsJson
                    }

                    val mergedContact = c1.copy(
                        firstName = selectedFirstName,
                        lastName = selectedLastName,
                        phone = selectedPhone,
                        email = selectedEmail,
                        jobTitle = selectedJob,
                        address = selectedAddress,
                        dobString = selectedDob,
                        anniversaryString = selectedAnniversary,
                        photoUri = c1.photoUri ?: c2.photoUri,
                        additionalFieldsJson = mergedFieldsJson,
                        additionalDatesJson = if (c1.additionalDatesJson.isNotBlank()) c1.additionalDatesJson else c2.additionalDatesJson,
                        googleContactId = c1.googleContactId ?: c2.googleContactId
                    )
                    onMerge(mergedContact)
                },
                colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Merge & Keep", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun ConflictFieldRow(
    label: String,
    val1: String,
    val2: String,
    current: String,
    onSelect1: () -> Unit,
    onSelect2: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A24), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val is1Selected = current == val1
            val is2Selected = current == val2

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (is1Selected) WaterBlue.copy(alpha = 0.2f) else Color(0xFF0F0F14))
                    .border(1.dp, if (is1Selected) WaterBlue else Color(0xFF262633), RoundedCornerShape(6.dp))
                    .clickable(onClick = onSelect1)
                    .padding(8.dp)
            ) {
                Text(
                    text = val1.ifBlank { "Empty" },
                    color = if (is1Selected) Color.White else Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = if (is1Selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (is2Selected) WaterBlue.copy(alpha = 0.2f) else Color(0xFF0F0F14))
                    .border(1.dp, if (is2Selected) WaterBlue else Color(0xFF262633), RoundedCornerShape(6.dp))
                    .clickable(onClick = onSelect2)
                    .padding(8.dp)
            ) {
                Text(
                    text = val2.ifBlank { "Empty" },
                    color = if (is2Selected) Color.White else Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = if (is2Selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}
