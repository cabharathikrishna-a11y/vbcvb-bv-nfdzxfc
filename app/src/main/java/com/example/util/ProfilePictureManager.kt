package com.example.util

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.widget.WidgetManager
import com.example.api.DevicePresenceManager
import com.example.util.TimeEngine
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Dedicated manager for discovering logged-in Gmail/Google accounts,
 * fetching high-resolution profile pictures, performing daily automated syncs,
 * caching bitmaps memory- & disk-safely, and providing universal avatar rendering.
 */
object ProfilePictureManager {

    private const val TAG = "ProfilePictureManager"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LAST_DAILY_SYNC = "last_daily_profile_pic_sync_timestamp"
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000L

    // In-memory memory cache for ultra-fast, smooth avatar rendering
    private val memoryCache = object : LruCache<String, Bitmap>(50) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // Reactive state flow to broadcast profile picture updates across all UI composables and widgets
    private val _avatarUpdatesFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val avatarUpdatesFlow: StateFlow<Map<String, String>> = _avatarUpdatesFlow.asStateFlow()

    data class AccountProfile(
        val email: String,
        val displayName: String,
        val photoUrl: String?,
        val isPrimary: Boolean
    )

    data class ProfileSyncResult(
        val success: Boolean,
        val photoUrl: String?,
        val email: String?,
        val message: String
    )

    /**
     * Converts standard Google profile photo URLs to high-resolution versions for crisp display.
     */
    fun getHighResPhotoUrl(originalUrl: String?, targetSize: Int = 384): String? {
        if (originalUrl.isNullOrBlank()) return null
        val trimmed = originalUrl.trim()
        return try {
            if (trimmed.contains("googleusercontent.com") || trimmed.contains("ggpht.com")) {
                // Replace =s96-c, =s128, etc. with =s{targetSize}-c
                val regex = Regex("=s\\d+(-c)?")
                if (regex.containsMatchIn(trimmed)) {
                    trimmed.replace(regex, "=s$targetSize-c")
                } else if (trimmed.contains("?")) {
                    "$trimmed&sz=$targetSize"
                } else {
                    "$trimmed=s$targetSize-c"
                }
            } else {
                trimmed
            }
        } catch (e: Exception) {
            trimmed
        }
    }

    /**
     * Discovers all logged-in Google / Gmail accounts on the device.
     */
    fun getLoggedInGoogleAccounts(context: Context): List<AccountProfile> {
        val accounts = mutableListOf<AccountProfile>()
        val seenEmails = mutableSetOf<String>()

        // 1. GoogleSignInAccount (Primary)
        try {
            val googleAccount: GoogleSignInAccount? = GmsUtils.getLastSignedInAccount(context)
                ?: try { GoogleSignIn.getLastSignedInAccount(context) } catch (_: Throwable) { null }

            if (googleAccount != null && !googleAccount.email.isNullOrBlank()) {
                val email = googleAccount.email!!.lowercase().trim()
                val photoUrl = getHighResPhotoUrl(googleAccount.photoUrl?.toString())
                val name = googleAccount.displayName ?: email.substringBefore("@")
                accounts.add(AccountProfile(email, name, photoUrl, isPrimary = true))
                seenEmails.add(email)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking GoogleSignInAccount: ${e.message}")
        }

        // 2. Firebase Auth Current User
        try {
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser != null && !firebaseUser.email.isNullOrBlank()) {
                val email = firebaseUser.email!!.lowercase().trim()
                if (!seenEmails.contains(email)) {
                    val photoUrl = getHighResPhotoUrl(firebaseUser.photoUrl?.toString())
                    val name = firebaseUser.displayName ?: email.substringBefore("@")
                    accounts.add(AccountProfile(email, name, photoUrl, isPrimary = accounts.isEmpty()))
                    seenEmails.add(email)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking FirebaseAuth currentUser: ${e.message}")
        }

        // 3. System AccountManager for Google Accounts on device
        try {
            val accountManager = AccountManager.get(context)
            val googleAccounts: Array<Account>? = accountManager.getAccountsByType("com.google")
            googleAccounts?.forEach { acct ->
                val email = acct.name.lowercase().trim()
                if (email.isNotEmpty() && !seenEmails.contains(email)) {
                    accounts.add(AccountProfile(email, email.substringBefore("@"), null, isPrimary = accounts.isEmpty()))
                    seenEmails.add(email)
                }
            }
        } catch (e: Exception) {
            // Permission or security limitation handled gracefully
            Log.d(TAG, "AccountManager query skipped: ${e.message}")
        }

        // 4. Fallback from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedEmail = prefs.getString("user_email", "")?.lowercase()?.trim() ?: ""
        if (savedEmail.isNotEmpty() && !seenEmails.contains(savedEmail)) {
            val savedName = prefs.getString("user_name", "") ?: savedEmail.substringBefore("@")
            val savedPhoto = prefs.getString("user_photo_url", "") ?: prefs.getString("user_emoji", "") ?: ""
            val photoUrl = if (savedPhoto.startsWith("http")) getHighResPhotoUrl(savedPhoto) else null
            accounts.add(AccountProfile(savedEmail, savedName, photoUrl, isPrimary = accounts.isEmpty()))
            seenEmails.add(savedEmail)
        }

        return accounts
    }

    /**
     * Checks all logged in Gmail/Google accounts and syncs the latest profile picture.
     * Runs automatically every day (24 hours) or when forced.
     */
    fun performDailyProfilePicSync(context: Context, force: Boolean = false, onComplete: ((ProfileSyncResult) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastSync = prefs.getLong(KEY_LAST_DAILY_SYNC, 0L)
            val now = System.currentTimeMillis()

            if (!force && (now - lastSync < ONE_DAY_MS) && lastSync > 0L) {
                Log.d(TAG, "Daily profile picture sync already ran in the last 24h. Skipping.")
                val currentPhoto = prefs.getString("user_photo_url", "") ?: prefs.getString("user_emoji", "") ?: ""
                val currentEmail = prefs.getString("user_email", "") ?: ""
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(ProfileSyncResult(true, currentPhoto, currentEmail, "Cache valid (checked within 24h)"))
                }
                return@launch
            }

            Log.i(TAG, "Starting daily Gmail profile picture verification and sync...")
            val accounts = getLoggedInGoogleAccounts(context)
            if (accounts.isEmpty()) {
                Log.w(TAG, "No logged-in Google accounts discovered for profile picture sync.")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(ProfileSyncResult(false, null, null, "No logged in accounts found"))
                }
                return@launch
            }

            val primaryAccount = accounts.firstOrNull { it.isPrimary } ?: accounts.first()
            val email = primaryAccount.email
            var latestPhotoUrl = primaryAccount.photoUrl

            // If primary account doesn't have a direct photoUrl, check if any other logged in account does
            if (latestPhotoUrl.isNullOrBlank()) {
                latestPhotoUrl = accounts.firstOrNull { !it.photoUrl.isNullOrBlank() }?.photoUrl
            }

            // High-res enhancement
            latestPhotoUrl = getHighResPhotoUrl(latestPhotoUrl)

            if (!latestPhotoUrl.isNullOrBlank()) {
                val currentStoredEmoji = prefs.getString("user_emoji", "") ?: ""
                val currentStoredPhoto = prefs.getString("user_photo_url", "") ?: ""
                val currentUsername = prefs.getString("current_username", "") ?: ""

                // 1. Download and pre-cache bitmap locally
                val downloadedBmp = downloadAndCacheAvatar(context, latestPhotoUrl)

                // 2. Update SharedPreferences
                val editor = prefs.edit()
                    .putString("user_photo_url", latestPhotoUrl)
                    .putString("user_emoji", latestPhotoUrl)
                    .putString("cached_avatar_$email", latestPhotoUrl)
                    .putLong("cached_avatar_time_$email", now)
                    .putLong(KEY_LAST_DAILY_SYNC, now)

                if (currentUsername.isNotEmpty()) {
                    editor.putString("user_emoji_$currentUsername", latestPhotoUrl)
                }
                editor.apply()

                // 3. Update memory map & reactive flow
                val currentMap = _avatarUpdatesFlow.value.toMutableMap()
                currentMap[email] = latestPhotoUrl
                if (currentUsername.isNotEmpty()) currentMap[currentUsername] = latestPhotoUrl
                _avatarUpdatesFlow.value = currentMap

                // 4. Sync updated profile photo to Firestore
                try {
                    val firestore = FirebaseFirestore.getInstance(
                        com.google.firebase.FirebaseApp.getInstance(),
                        "main"
                    )
                    val sanitized = DevicePresenceManager.sanitizeEmail(email)
                    val updateMap = hashMapOf<String, Any>(
                        "emoji" to latestPhotoUrl,
                        "photo_url" to latestPhotoUrl,
                        "email" to email,
                        "last_profile_sync_ts" to now
                    )
                    firestore.collection("users").document(sanitized)
                        .set(updateMap, SetOptions.merge())

                    if (sanitized != email) {
                        firestore.collection("users").document(email)
                            .set(updateMap, SetOptions.merge())
                    }
                    Log.d(TAG, "Successfully synced latest Google profile pic to Firestore for: $email")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing profile pic to Firestore: ${e.message}")
                }

                // 5. Sync to Firebase Realtime Database
                try {
                    val dbUrl = com.example.api.FirebaseConfig.getDatabaseUrl(context)
                    if (dbUrl.isNotBlank()) {
                        val sanitized = DevicePresenceManager.sanitizeEmail(email)
                        val rtdb = com.google.firebase.database.FirebaseDatabase.getInstance(dbUrl)
                        val userRoot = rtdb.getReference("FOCUS_TIMMER").child("USER").child(sanitized)
                        userRoot.child("ARENA").child("CustomEmoji").setValue(latestPhotoUrl)
                        userRoot.child("ARENA").child("Last_Updated").setValue(TimeEngine.getTrueTimeMs())
                        
                        rtdb.getReference("FOCUS_TIMMER").child("LEADERBOARD").child(sanitized)
                            .child("CustomEmoji").setValue(latestPhotoUrl)

                        userRoot.child("ACTIVE_FOCUS_TIMER").child("User_Emoji").setValue(latestPhotoUrl)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing profile pic to RTDB: ${e.message}")
                }

                // 6. Update all Home Screen and Friends Focus Widgets
                try {
                    WidgetManager.updateFriendsFocusWidget(context)
                    WidgetManager.updateAllWidgets(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed refreshing widgets with new profile photo: ${e.message}")
                }

                Log.i(TAG, "Daily profile picture sync complete: Successfully updated photo for $email -> $latestPhotoUrl")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(ProfileSyncResult(true, latestPhotoUrl, email, "Successfully synced new profile pic"))
                }
            } else {
                prefs.edit().putLong(KEY_LAST_DAILY_SYNC, now).apply()
                Log.d(TAG, "Daily profile sync complete: No photo URL found for account $email.")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(ProfileSyncResult(true, null, email, "No photo URL present on account"))
                }
            }
        }
    }

    /**
     * Resolves an avatar string for a given user or friend by email/username/custom emoji.
     */
    fun resolveUserAvatarString(context: Context, emailOrUsername: String?, fallbackEmoji: String? = null): String {
        if (emailOrUsername.isNullOrBlank()) return fallbackEmoji?.ifEmpty { "👤" } ?: "👤"
        val clean = emailOrUsername.trim()

        // 1. Check in-memory reactive flow map
        _avatarUpdatesFlow.value[clean]?.let { return it }

        // 2. If it's already a URL, base64 or custom emoji, return it
        if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("base64:") || (clean.length in 1..4 && !clean.contains("@") && !clean.contains("."))) {
            return clean
        }

        // 3. Check SharedPreferences disk cache
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString("cached_avatar_$clean", "")?.orEmpty()
            ?.ifEmpty { prefs.getString("user_emoji_$clean", "") }
            ?.ifEmpty { if (clean == prefs.getString("user_email", "") || clean == prefs.getString("current_username", "")) prefs.getString("user_emoji", "") else "" }
            ?.orEmpty()

        if (!cached.isNullOrEmpty() && cached != "👤") {
            return cached
        }

        return fallbackEmoji?.ifEmpty { "👤" } ?: "👤"
    }

    /**
     * Synchronously decodes or retrieves a cached avatar bitmap (ideal for Widgets & Drawables).
     */
    fun getAvatarBitmap(context: Context, avatarStr: String?, targetPx: Int): Bitmap? {
        if (avatarStr.isNullOrBlank()) return null
        val trimmed = avatarStr.trim()
        if (trimmed.isEmpty() || trimmed == "👤" || trimmed == "🎯" || trimmed == "💤") return null

        val cacheKey = "${trimmed.hashCode()}_$targetPx"
        memoryCache.get(cacheKey)?.let { return it }

        try {
            // 1. Base64 format
            if (trimmed.startsWith("base64:") || trimmed.startsWith("data:image/") || (trimmed.length > 80 && !trimmed.contains(" ") && !trimmed.startsWith("http"))) {
                val rawData = when {
                    trimmed.startsWith("base64:") -> trimmed.substringAfter("base64:")
                    trimmed.contains("base64,") -> trimmed.substringAfter("base64,")
                    else -> trimmed
                }
                val bytes = Base64.decode(rawData, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val circular = getCircularBitmap(bmp, targetPx)
                    memoryCache.put(cacheKey, circular)
                    return circular
                }
            }

            // 2. HTTP/HTTPS URL (Google profile photo, high-res avatar)
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val highResUrl = getHighResPhotoUrl(trimmed, targetPx) ?: trimmed
                val cacheDir = File(context.cacheDir, "avatar_cache").apply { if (!exists()) mkdirs() }
                val cacheFile = File(cacheDir, "avatar_${abs(highResUrl.hashCode())}.jpg")

                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    if (bmp != null) {
                        val circular = getCircularBitmap(bmp, targetPx)
                        memoryCache.put(cacheKey, circular)
                        return circular
                    }
                }

                // Download if on background thread
                val downloadedBmp = downloadAndCacheAvatar(context, highResUrl)
                if (downloadedBmp != null) {
                    val circular = getCircularBitmap(downloadedBmp, targetPx)
                    memoryCache.put(cacheKey, circular)
                    return circular
                }
            }

            // 3. Local file or content URI
            if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
                val path = trimmed.removePrefix("file://")
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    val circular = getCircularBitmap(bmp, targetPx)
                    memoryCache.put(cacheKey, circular)
                    return circular
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed retrieving avatar bitmap for '$trimmed': ${e.message}")
        }
        return null
    }

    /**
     * Downloads an avatar from the web and caches it to disk.
     */
    fun downloadAndCacheAvatar(context: Context, urlString: String): Bitmap? {
        return try {
            val highResUrl = getHighResPhotoUrl(urlString) ?: urlString
            val cacheDir = File(context.cacheDir, "avatar_cache").apply { if (!exists()) mkdirs() }
            val cacheFile = File(cacheDir, "avatar_${abs(highResUrl.hashCode())}.jpg")

            val url = URL(highResUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doInput = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Android App")
            connection.connect()

            val inputStream = connection.inputStream
            val bytes = inputStream.readBytes()
            inputStream.close()
            connection.disconnect()

            cacheFile.writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading avatar from $urlString: ${e.message}")
            null
        }
    }

    /**
     * Produces a clean circular-cropped bitmap at the requested size in pixels.
     */
    fun getCircularBitmap(src: Bitmap, sizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, sizePx, sizePx)
        val rectF = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())

        canvas.drawARGB(0, 0, 0, 0)
        paint.color = -0x1
        canvas.drawOval(rectF, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        // Scale & center crop
        val srcWidth = src.width
        val srcHeight = src.height
        val minDim = minOf(srcWidth, srcHeight)
        val srcX = (srcWidth - minDim) / 2
        val srcY = (srcHeight - minDim) / 2
        val srcRect = Rect(srcX, srcY, srcX + minDim, srcY + minDim)

        canvas.drawBitmap(src, srcRect, rect, paint)
        return output
    }

    /**
     * Deterministic vibrant pastel color generator for initials fallback avatar.
     */
    fun getAvatarBackgroundColor(seed: String): Color {
        val hash = abs(seed.hashCode())
        val palette = listOf(
            Color(0xFF38BDF8), // Water Blue
            Color(0xFF818CF8), // Indigo
            Color(0xFFA78BFA), // Purple
            Color(0xFFF472B6), // Pink
            Color(0xFFFB7185), // Rose
            Color(0xFF34D399), // Emerald
            Color(0xFFFBBF24), // Amber
            Color(0xFF2DD4BF), // Teal
            Color(0xFF60A5FA)  // Sky
        )
        return palette[hash % palette.size]
    }
}

/**
 * Universal, high-performance UserAvatar composable used all across the app
 * (Friends Focus, Details Dialog, Leaderboard, Arena, Chat, LiveSphere, Profile Setup, Settings).
 */
@Composable
fun UserAvatar(
    emojiOrBase64: String?,
    modifier: Modifier = Modifier,
    email: String? = null,
    displayName: String? = null,
    fontSize: TextUnit = 18.sp,
    size: Dp = 24.dp,
    fallback: String = "",
    border: BorderStroke? = null,
    isFocusing: Boolean? = null,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val avatarUpdates by ProfilePictureManager.avatarUpdatesFlow.collectAsState()

    // Determine the most up-to-date avatar string
    val resolvedValue = remember(emojiOrBase64, email, avatarUpdates) {
        var str = emojiOrBase64?.trim() ?: ""
        if ((str.isEmpty() || str == "👤") && !email.isNullOrBlank()) {
            val fromManager = ProfilePictureManager.resolveUserAvatarString(context, email)
            if (fromManager.isNotEmpty() && fromManager != "👤") {
                str = fromManager
            }
        }
        str
    }

    val isUrl = resolvedValue.startsWith("http://") || resolvedValue.startsWith("https://") ||
            (resolvedValue.contains("googleusercontent.com") || resolvedValue.contains("ggpht.com"))
    val isBase64 = resolvedValue.startsWith("base64:") || (resolvedValue.length > 80 && !resolvedValue.contains(" ") && !resolvedValue.startsWith("http"))

    val effectiveModifier = modifier
        .size(size)
        .let { mod -> if (onClick != null) mod.clickable(onClick = onClick) else mod }

    Box(modifier = effectiveModifier, contentAlignment = Alignment.Center) {
        when {
            // 1. Placeholder or Default Person Icon / Initials
            resolvedValue.isEmpty() || resolvedValue == "👤" -> {
                val initials = fallback.ifEmpty { displayName?.take(2)?.uppercase() ?: "" }
                if (initials.isNotEmpty() && initials != "👤") {
                    val bgColor = remember(displayName ?: fallback ?: email) {
                        ProfilePictureManager.getAvatarBackgroundColor(displayName ?: fallback ?: email ?: "User")
                    }
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .background(bgColor.copy(alpha = 0.85f))
                            .let { if (border != null) it.border(border, CircleShape) else it.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            fontSize = (size.value * 0.42f).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .let { if (border != null) it.border(border, CircleShape) else it.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User Avatar Placeholder",
                            tint = Color.LightGray.copy(alpha = 0.7f),
                            modifier = Modifier.size(size * 0.58f)
                        )
                    }
                }
            }

            // 2. Base64 Bitmap
            isBase64 -> {
                val rawData = if (resolvedValue.startsWith("base64:")) resolvedValue.substringAfter("base64:") else resolvedValue
                val bitmap = remember(rawData) {
                    try {
                        val decoded = Base64.decode(rawData, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "User Avatar",
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .let { if (border != null) it.border(border, CircleShape) else it.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape) },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    FallbackAvatarInitials(size, fallback, displayName, email, border)
                }
            }

            // 3. HTTP / HTTPS Image URL (Google Profile Photo, Gravatar, Cloud Storage)
            isUrl -> {
                val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
                val highResUrl = remember(resolvedValue, targetPx) {
                    ProfilePictureManager.getHighResPhotoUrl(resolvedValue, targetPx.coerceAtLeast(192)) ?: resolvedValue
                }

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(highResUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "User Profile Picture",
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .let { if (border != null) it.border(border, CircleShape) else it.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape) },
                    contentScale = ContentScale.Crop
                )
            }

            // 4. Custom Emoji or Short Text Symbol (e.g., 🚀, 🦊, 💡)
            else -> {
                val isSingleEmoji = resolvedValue.length in 1..4
                if (isSingleEmoji) {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .let { if (border != null) it.border(border, CircleShape) else it.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resolvedValue,
                            fontSize = (size.value * 0.55f).sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                } else {
                    FallbackAvatarInitials(size, fallback, displayName, email, border)
                }
            }
        }

        // Optional Live Focusing Pulse Indicator
        if (isFocusing == true) {
            Box(
                modifier = Modifier
                    .size(size * 0.32f)
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E676))
                    .border(1.5.dp, Color.Black, CircleShape)
            )
        }
    }
}

@Composable
private fun FallbackAvatarInitials(
    size: Dp,
    fallback: String,
    displayName: String?,
    email: String?,
    border: BorderStroke?
) {
    val initials = fallback.ifEmpty { displayName?.take(2)?.uppercase() ?: "" }
    if (initials.isNotEmpty() && initials != "👤") {
        val bgColor = remember(displayName ?: fallback ?: email) {
            ProfilePictureManager.getAvatarBackgroundColor(displayName ?: fallback ?: email ?: "User")
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bgColor.copy(alpha = 0.85f))
                .let { if (border != null) it.border(border, CircleShape) else it.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .let { if (border != null) it.border(border, CircleShape) else it.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "User Avatar",
                tint = Color.LightGray.copy(alpha = 0.7f),
                modifier = Modifier.size(size * 0.58f)
            )
        }
    }
}
