package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object ContactSocialHelper {

    enum class CustomFieldType {
        INSTAGRAM_ID,
        INSTAGRAM_LINK,
        SNAPCHAT_ID,
        SNAPCHAT_LINK,
        TWITTER_ID,
        TWITTER_LINK,
        TELEGRAM_ID,
        TELEGRAM_LINK,
        FACEBOOK_ID,
        FACEBOOK_LINK,
        LINKEDIN_ID,
        LINKEDIN_LINK,
        YOUTUBE_ID,
        YOUTUBE_LINK,
        GENERAL_LINK,
        TEXT
    }

    enum class SocialBrand {
        INSTAGRAM,
        SNAPCHAT,
        TWITTER,
        TELEGRAM,
        FACEBOOK,
        LINKEDIN,
        YOUTUBE,
        GENERAL_LINK
    }

    data class RecognizedField(
        val key: String,
        val value: String,
        val type: CustomFieldType,
        val actionUrl: String? = null,
        val displayHandle: String = value,
        val brand: SocialBrand? = when (type) {
            CustomFieldType.INSTAGRAM_ID, CustomFieldType.INSTAGRAM_LINK -> SocialBrand.INSTAGRAM
            CustomFieldType.SNAPCHAT_ID, CustomFieldType.SNAPCHAT_LINK -> SocialBrand.SNAPCHAT
            CustomFieldType.TWITTER_ID, CustomFieldType.TWITTER_LINK -> SocialBrand.TWITTER
            CustomFieldType.TELEGRAM_ID, CustomFieldType.TELEGRAM_LINK -> SocialBrand.TELEGRAM
            CustomFieldType.FACEBOOK_ID, CustomFieldType.FACEBOOK_LINK -> SocialBrand.FACEBOOK
            CustomFieldType.LINKEDIN_ID, CustomFieldType.LINKEDIN_LINK -> SocialBrand.LINKEDIN
            CustomFieldType.YOUTUBE_ID, CustomFieldType.YOUTUBE_LINK -> SocialBrand.YOUTUBE
            CustomFieldType.GENERAL_LINK -> SocialBrand.GENERAL_LINK
            CustomFieldType.TEXT -> null
        }
    ) {
        val normalizedHandle: String
            get() = displayHandle.lowercase().trim().removePrefix("@").trimEnd('/').substringAfterLast('/')
    }

    /**
     * Parses the stored additionalFieldsJson string safely into a list of key-value pairs.
     * Supports both standard "key:value;key2:value2" formats and multi-colon values (like URLs).
     */
    fun parseCustomFields(rawString: String): List<Pair<String, String>> {
        if (rawString.isBlank()) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val pairs = rawString.split(";")
        for (pair in pairs) {
            val colonIdx = pair.indexOf(':')
            if (colonIdx > 0) {
                val key = pair.substring(0, colonIdx).trim()
                val value = pair.substring(colonIdx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    result.add(key to value)
                }
            }
        }
        return result
    }

    /**
     * Serializes a list of key-value pairs into a clean string.
     */
    fun serializeCustomFields(fields: List<Pair<String, String>>): String {
        return fields
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .joinToString(";") { "${it.first.trim()}:${it.second.trim()}" }
    }

    /**
     * Analyzes a key-value pair and classifies it into social types, web link, or general text.
     */
    fun classifyField(key: String, value: String): RecognizedField {
        val lowerKey = key.lowercase().trim()
        val lowerVal = value.lowercase().trim()

        // 1. Instagram recognition
        if (lowerKey.contains("insta") || lowerKey == "ig" || lowerKey.contains("instagram") ||
            lowerVal.contains("instagram.com") || lowerVal.contains("instagr.am")
        ) {
            val isLink = lowerKey.contains("link") || lowerKey.contains("url") || lowerKey.contains("dp") || lowerKey.contains("profile") || lowerVal.contains("instagram.com") || lowerVal.contains("instagr.am")
            val url = formatUrl(value, "https://instagram.com/")
            val handle = extractHandleFromUrl(url) ?: if (value.startsWith("@")) value.trim() else "@${value.trim().removePrefix("@")}"
            return RecognizedField(
                key = key,
                value = value,
                type = if (isLink) CustomFieldType.INSTAGRAM_LINK else CustomFieldType.INSTAGRAM_ID,
                actionUrl = url,
                displayHandle = handle
            )
        }

        // 2. Snapchat recognition
        if (lowerKey.contains("snap") || lowerKey.contains("snapchat") || lowerVal.contains("snapchat.com")) {
            val isLink = lowerKey.contains("link") || lowerKey.contains("url") || lowerVal.contains("snapchat.com")
            val url = formatUrl(value, "https://snapchat.com/add/")
            val handle = extractHandleFromUrl(url) ?: if (value.startsWith("@")) value.trim() else "@${value.trim().removePrefix("@")}"
            return RecognizedField(
                key = key,
                value = value,
                type = if (isLink) CustomFieldType.SNAPCHAT_LINK else CustomFieldType.SNAPCHAT_ID,
                actionUrl = url,
                displayHandle = handle
            )
        }

        // 3. Twitter / X recognition
        if (lowerKey.contains("twitter") || lowerKey == "x" || lowerKey.contains("tweet") || lowerVal.contains("twitter.com") || lowerVal.contains("x.com")) {
            val isLink = lowerVal.contains("twitter.com") || lowerVal.contains("x.com") || lowerKey.contains("link")
            val url = formatUrl(value, "https://x.com/")
            val handle = extractHandleFromUrl(url) ?: if (value.startsWith("@")) value.trim() else "@${value.trim().removePrefix("@")}"
            return RecognizedField(
                key = key,
                value = value,
                type = if (isLink) CustomFieldType.TWITTER_LINK else CustomFieldType.TWITTER_ID,
                actionUrl = url,
                displayHandle = handle
            )
        }

        // 4. Telegram recognition
        if (lowerKey.contains("telegram") || lowerKey == "tg" || lowerVal.contains("t.me")) {
            val isLink = lowerVal.contains("t.me") || lowerKey.contains("link")
            val url = formatUrl(value, "https://t.me/")
            val handle = extractHandleFromUrl(url) ?: if (value.startsWith("@")) value.trim() else "@${value.trim().removePrefix("@")}"
            return RecognizedField(
                key = key,
                value = value,
                type = if (isLink) CustomFieldType.TELEGRAM_LINK else CustomFieldType.TELEGRAM_ID,
                actionUrl = url,
                displayHandle = handle
            )
        }

        // 5. Facebook recognition
        if (lowerKey.contains("facebook") || lowerKey == "fb" || lowerVal.contains("facebook.com")) {
            val isLink = lowerVal.contains("facebook.com") || lowerKey.contains("link")
            val url = formatUrl(value, "https://facebook.com/")
            val handle = extractHandleFromUrl(url) ?: value
            return RecognizedField(
                key = key,
                value = value,
                type = if (isLink) CustomFieldType.FACEBOOK_LINK else CustomFieldType.FACEBOOK_ID,
                actionUrl = url,
                displayHandle = handle
            )
        }

        // 6. LinkedIn recognition
        if (lowerKey.contains("linkedin") || lowerVal.contains("linkedin.com")) {
            val isLink = lowerVal.contains("linkedin.com") || lowerKey.contains("link")
            val url = formatUrl(value, "https://linkedin.com/in/")
            val handle = extractHandleFromUrl(url) ?: value
            return RecognizedField(
                key = key,
                value = value,
                type = if (isLink) CustomFieldType.LINKEDIN_LINK else CustomFieldType.LINKEDIN_ID,
                actionUrl = url,
                displayHandle = handle
            )
        }

        // 7. YouTube recognition
        if (lowerKey.contains("youtube") || lowerKey == "yt" || lowerVal.contains("youtube.com") || lowerVal.contains("youtu.be")) {
            val url = if (value.startsWith("http")) value else "https://youtube.com/@${value.trim().removePrefix("@")}"
            return RecognizedField(
                key = key,
                value = value,
                type = CustomFieldType.YOUTUBE_LINK,
                actionUrl = url,
                displayHandle = value
            )
        }

        // 8. General Web Link
        if (lowerVal.startsWith("http://") || lowerVal.startsWith("https://") || lowerVal.startsWith("www.")) {
            val url = if (lowerVal.startsWith("www.")) "https://$value" else value
            return RecognizedField(key, value, CustomFieldType.GENERAL_LINK, url, value)
        }

        // 9. Generic Text
        return RecognizedField(key, value, CustomFieldType.TEXT, null, value)
    }

    /**
     * Deduplicates custom fields so that redundant entries pointing to the same social account
     * (e.g. "Insta ID: bharath" and "Insta DP link: https://instagram.com/bharath") are merged into a single entry.
     */
    fun deduplicateCustomFields(fields: List<Pair<String, String>>): List<RecognizedField> {
        if (fields.isEmpty()) return emptyList()

        val recognizedList = fields.map { classifyField(it.first, it.second) }
        val result = mutableListOf<RecognizedField>()

        val socialByBrand = mutableMapOf<SocialBrand, MutableList<RecognizedField>>()
        val nonSocialFields = mutableListOf<RecognizedField>()

        for (rf in recognizedList) {
            val brand = rf.brand
            if (brand != null && brand != SocialBrand.GENERAL_LINK) {
                socialByBrand.getOrPut(brand) { mutableListOf() }.add(rf)
            } else if (brand == SocialBrand.GENERAL_LINK) {
                val normalizedUrl = rf.actionUrl?.trimEnd('/')?.lowercase() ?: rf.value.lowercase()
                if (result.none { it.actionUrl?.trimEnd('/')?.lowercase() == normalizedUrl }) {
                    result.add(rf)
                }
            } else {
                if (nonSocialFields.none { it.key.equals(rf.key, ignoreCase = true) && it.value.equals(rf.value, ignoreCase = true) }) {
                    nonSocialFields.add(rf)
                }
            }
        }

        // For each social brand, deduplicate by normalized handle or merge single account entries
        for ((brand, brandFields) in socialByBrand) {
            val handleGroups = mutableMapOf<String, MutableList<RecognizedField>>()
            for (f in brandFields) {
                val handleKey = f.normalizedHandle.ifEmpty { "default" }
                handleGroups.getOrPut(handleKey) { mutableListOf() }.add(f)
            }

            for ((_, group) in handleGroups) {
                val bestEntry = group.firstOrNull { it.actionUrl != null && it.displayHandle.startsWith("@") }
                    ?: group.firstOrNull { it.actionUrl != null }
                    ?: group.first()

                val brandName = when (brand) {
                    SocialBrand.INSTAGRAM -> "Instagram"
                    SocialBrand.SNAPCHAT -> "Snapchat"
                    SocialBrand.TWITTER -> "Twitter / X"
                    SocialBrand.TELEGRAM -> "Telegram"
                    SocialBrand.FACEBOOK -> "Facebook"
                    SocialBrand.LINKEDIN -> "LinkedIn"
                    SocialBrand.YOUTUBE -> "YouTube"
                    SocialBrand.GENERAL_LINK -> bestEntry.key
                }

                val unified = bestEntry.copy(key = brandName)
                result.add(unified)
            }
        }

        result.addAll(nonSocialFields)
        return result
    }

    /**
     * Returns the list of unique social brands to display as logos beside the folder bubble.
     * Deduplicated so each platform logo only appears once.
     */
    fun getSocialLogosForContact(additionalFieldsJson: String): List<SocialBrand> {
        if (additionalFieldsJson.isBlank()) return emptyList()
        val rawFields = parseCustomFields(additionalFieldsJson)
        val deduplicated = deduplicateCustomFields(rawFields)
        val brands = mutableListOf<SocialBrand>()
        for (f in deduplicated) {
            val b = f.brand ?: continue
            if (!brands.contains(b)) {
                brands.add(b)
            }
        }
        return brands
    }

    private fun formatUrl(input: String, defaultBaseUrl: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("www.") -> "https://$trimmed"
            trimmed.contains(".com/") || trimmed.contains(".me/") -> "https://$trimmed"
            else -> defaultBaseUrl + trimmed.removePrefix("@")
        }
    }

    private fun extractHandleFromUrl(url: String): String? {
        return try {
            val clean = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            val segment = clean.substringAfterLast('/')
            if (segment.isNotBlank() && !segment.contains("instagram") && !segment.contains("snapchat") && !segment.contains("facebook") && !segment.contains("twitter") && !segment.contains("linkedin")) {
                "@$segment"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Attempts to open the Instagram profile in the Instagram app first, falling back to browser.
     */
    fun openInstagram(context: Context, handleOrUrl: String) {
        val cleanHandle = handleOrUrl.trim()
            .substringAfterLast("instagram.com/")
            .substringAfterLast("/")
            .removePrefix("@")
            .trimEnd('/')

        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://instagram.com/_u/$cleanHandle")).apply {
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(appIntent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/$cleanHandle")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(webIntent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(context, "Unable to open Instagram link", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Attempts to open Snapchat in the Snapchat app first, falling back to browser.
     */
    fun openSnapchat(context: Context, handleOrUrl: String) {
        val cleanHandle = handleOrUrl.trim()
            .substringAfterLast("snapchat.com/add/")
            .substringAfterLast("/")
            .removePrefix("@")
            .trimEnd('/')

        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("snapchat://add/$cleanHandle")).apply {
            setPackage("com.snapchat.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(appIntent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://snapchat.com/add/$cleanHandle")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(webIntent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(context, "Unable to open Snapchat link", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Opens general web URL.
     */
    fun openWebUrl(context: Context, url: String) {
        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Unable to open link", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Attempts to open WhatsApp chat for a given phone number directly, falling back to browser (wa.me).
     */
    fun openWhatsApp(context: Context, rawPhone: String, message: String? = null) {
        val cleanPhone = rawPhone.replace(Regex("[^0-9+]"), "").let {
            if (it.startsWith("+")) it.substring(1) else it
        }
        if (cleanPhone.isBlank()) {
            android.widget.Toast.makeText(context, "No valid phone number for WhatsApp", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val encodedMsg = if (!message.isNullOrBlank()) Uri.encode(message) else ""
        val waUriString = if (encodedMsg.isNotEmpty()) {
            "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg"
        } else {
            "https://api.whatsapp.com/send?phone=$cleanPhone"
        }

        // 1. Try standard WhatsApp app
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUriString)).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(appIntent)
        } catch (e: Exception) {
            // 2. Try WhatsApp Business app
            val waBusinessIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUriString)).apply {
                setPackage("com.whatsapp.w4b")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(waBusinessIntent)
            } catch (e2: Exception) {
                // 3. Fallback to browser (wa.me)
                val fallbackUri = if (encodedMsg.isNotEmpty()) {
                    "https://wa.me/$cleanPhone?text=$encodedMsg"
                } else {
                    "https://wa.me/$cleanPhone"
                }
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(webIntent)
                } catch (e3: Exception) {
                    android.widget.Toast.makeText(context, "Unable to open WhatsApp redirection", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
