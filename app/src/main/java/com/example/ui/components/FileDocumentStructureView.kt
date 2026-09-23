package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.WaterBlue
import com.example.util.FileActivityLog
import com.example.util.FileActivityLogger

@Composable
fun FileDocumentStructureView(
    folderHeading: String,
    folderDescription: String,
    folderIcon: androidx.compose.ui.graphics.vector.ImageVector,
    folderColor: Color,
    files: List<ExplorerFile>,
    onBackClick: (() -> Unit)? = null,
    onCreateFolderClick: (() -> Unit)? = null,
    onUploadFileClick: (() -> Unit)? = null,
    onFileClick: (ExplorerFile) -> Unit,
    onOptionsClick: (ExplorerFile) -> Unit,
    onCopyClick: (ExplorerFile) -> Unit,
    onMoveClick: (ExplorerFile) -> Unit,
    onRenameClick: (ExplorerFile) -> Unit,
    onDeleteClick: (ExplorerFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp)
    ) {
        // Back Header if inside subfolder
        if (onBackClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBackClick() }
                    .padding(vertical = 6.dp)
                    .testTag("folder_back_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = WaterBlue,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Back to Folder Structure",
                    color = WaterBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // BIG HEADING (Folder)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, folderColor.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(folderColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = folderIcon,
                            contentDescription = null,
                            tint = folderColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    val actualFilesCount = remember(files) { files.count { it.fileMime != "inode/directory" } }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FOLDER: ${folderHeading.uppercase()}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$folderDescription ($actualFilesCount files)",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }

                if (onCreateFolderClick != null || onUploadFileClick != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onCreateFolderClick != null) {
                            Button(
                                onClick = onCreateFolderClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E2640),
                                    contentColor = WaterBlue
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("create_folder_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "Create Folder",
                                    tint = WaterBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Create Folder",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (onUploadFileClick != null) {
                            Button(
                                onClick = onUploadFileClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF261D3B),
                                    contentColor = Color(0xFFCE93D8)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("upload_file_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = "Upload File",
                                    tint = Color(0xFFCE93D8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Upload File",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                    Text("No files in this folder", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Create a folder (manual / Google Drive) or upload a file (device / Drive link).",
                        color = Color.Gray,
                        fontSize = 11.5.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(files, key = { idx, file -> "${file.path.ifEmpty { file.name }}_${file.timestamp}_$idx" }) { _, file ->
                    FileDocumentBulletCard(
                        context = context,
                        file = file,
                        folderHeading = folderHeading,
                        onFileClick = { onFileClick(file) },
                        onOptionsClick = { onOptionsClick(file) },
                        onCopyClick = { onCopyClick(file) },
                        onMoveClick = { onMoveClick(file) },
                        onRenameClick = { onRenameClick(file) },
                        onDeleteClick = { onDeleteClick(file) }
                    )
                }
            }
        }
    }
}

@Composable
fun FileDocumentBulletCard(
    context: Context,
    file: ExplorerFile,
    folderHeading: String,
    onFileClick: () -> Unit,
    onOptionsClick: () -> Unit,
    onCopyClick: () -> Unit,
    onMoveClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val fileKey = file.appFileRef?.uriString?.ifEmpty { file.path } ?: (file.path + "/" + file.name)
    val sequentialId = FileActivityLogger.allocateSequentialFileId(context, fileKey)

    val formattedSize = if (file.appFileRef != null && file.appFileRef.size > 0) {
        formatFileSize(file.appFileRef.size)
    } else {
        "1.2 MB"
    }

    val formattedDate = FileActivityLog.formatDateWithDay(file.timestamp)
    val uploaderDetails = "Bharathi Krishna (bharathikrishna9440@gmail.com)"
    val docUrl = FileActivityLogger.GOOGLE_DOC_URL

    var isExpanded by remember { mutableStateOf(false) }

    val isImage = remember(file) {
        file.type.equals("image", ignoreCase = true) ||
        file.fileMime.startsWith("image/", ignoreCase = true) ||
        file.name.endsWith(".png", ignoreCase = true) ||
        file.name.endsWith(".jpg", ignoreCase = true) ||
        file.name.endsWith(".jpeg", ignoreCase = true) ||
        file.name.endsWith(".webp", ignoreCase = true) ||
        file.name.startsWith("photo_", ignoreCase = true)
    }

    val imageModel: Any? = remember(file) {
        val raw = file.appFileRef?.uriString?.ifEmpty { null } ?: file.path.ifEmpty { null }
        when {
            raw == null -> null
            raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> java.io.File(raw)
        }
    }

    val (fileIcon, fileIconTint) = remember(file, isImage) {
        when {
            isImage -> Icons.Default.Image to Color(0xFF4CAF50)
            file.type.equals("video", ignoreCase = true) || file.fileMime.startsWith("video/") -> Icons.Default.VideoLibrary to Color(0xFFFF5252)
            file.type.equals("audio", ignoreCase = true) || file.fileMime.startsWith("audio/") -> Icons.Default.AudioFile to Color(0xFF00E5FF)
            file.name.endsWith(".pdf", ignoreCase = true) || file.fileMime == "application/pdf" -> Icons.Default.PictureAsPdf to Color(0xFFE53935)
            file.fileMime == "inode/directory" -> Icons.Default.Folder to Color(0xFFFFB300)
            file.name.endsWith(".doc", ignoreCase = true) || file.name.endsWith(".docx", ignoreCase = true) -> Icons.Default.Description to Color(0xFF4285F4)
            file.name.endsWith(".xls", ignoreCase = true) || file.name.endsWith(".xlsx", ignoreCase = true) -> Icons.Default.TableChart to Color(0xFF10B981)
            else -> Icons.Default.InsertDriveFile to WaterBlue
        }
    }

    val contactInitial = remember(file.name) {
        if (file.name.startsWith("photo_", ignoreCase = true)) {
            file.name.substringAfter("photo_").firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: ""
        } else ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("file_bullet_card_${file.name}"),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // COMPACT ROW: [Mini Photo Preview] [Name & Sequential ID \n Date & Time] [3-Dots Button]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // LEFT: Mini Preview of Photo / File Icon
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1B1B24))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .clickable { onFileClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isImage && imageModel != null) {
                        SubcomposeAsyncImage(
                            model = imageModel,
                            contentDescription = file.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp)),
                            loading = {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = WaterBlue)
                                }
                            },
                            error = {
                                if (contactInitial.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(WaterBlue.copy(alpha = 0.25f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = contactInitial,
                                            color = WaterBlue,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                                }
                            }
                        )
                    } else if (contactInitial.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(WaterBlue.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = contactInitial,
                                color = WaterBlue,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Icon(
                            imageVector = fileIcon,
                            contentDescription = null,
                            tint = fileIconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // CENTER: Name on top, Date & Time below
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onFileClick() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(WaterBlue.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = sequentialId,
                                color = WaterBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = file.name,
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = formattedDate,
                        color = Color.Gray,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // RIGHT: 3 Dots icon button - click extends the card to show remaining details
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isExpanded) WaterBlue.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                        .testTag("expand_file_details_${file.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = if (isExpanded) "Collapse details" else "Extend card details",
                        tint = if (isExpanded) WaterBlue else Color.LightGray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // EXTENDED CARD DETAILS: Shown when 3-dots is clicked
            if (isExpanded) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 2.dp))

                // Sub-bullet details
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SubBulletItem(label = "Sequential File ID", value = sequentialId, valueColor = WaterBlue)
                    SubBulletItem(label = "Format", value = file.fileMime.ifEmpty { file.type.uppercase() })
                    SubBulletItem(label = "File Size", value = formattedSize)
                    SubBulletItem(label = "Uploader", value = uploaderDetails)
                    SubBulletItem(label = "Uploaded Date & Time", value = formattedDate)
                    SubBulletItem(label = "Permissions", value = "Anyone with link can view & download", valueColor = Color(0xFF81C784))

                    // Google Drive Sync Link sub-bullet point
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clickable { openDocLink(context, docUrl) }
                            .padding(vertical = 2.dp)
                    ) {
                        Text(text = "◦", color = Color.Gray, fontSize = 12.sp)
                        Text(text = "Google Drive Sync Doc:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = "Open Shared Google Doc 🔗",
                            color = WaterBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Quick Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onFileClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open", fontSize = 10.sp)
                    }

                    OutlinedButton(
                        onClick = onCopyClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF60A5FA)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF60A5FA).copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 10.sp)
                    }

                    OutlinedButton(
                        onClick = onMoveClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC084FC)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC084FC).copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Move", fontSize = 10.sp)
                    }

                    OutlinedButton(
                        onClick = onRenameClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFBBF24)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rename", fontSize = 10.sp)
                    }

                    OutlinedButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", fontSize = 10.sp)
                    }

                    OutlinedButton(
                        onClick = onOptionsClick,
                        modifier = Modifier.size(width = 36.dp, height = 32.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WaterBlue),
                        border = androidx.compose.foundation.BorderStroke(1.dp, WaterBlue.copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = "More Options", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubBulletItem(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = "◦", color = Color.Gray, fontSize = 12.sp)
        Text(text = "$label:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(text = value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) digitGroups = units.size - 1
    val formatted = String.format(java.util.Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    return formatted
}

private fun openDocLink(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Google Doc Link", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
    }
}
