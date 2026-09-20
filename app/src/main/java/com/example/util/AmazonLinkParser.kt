package com.example.util

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ParsedAmazonProduct(
    val title: String,
    val cost: Double,
    val imageUrl: String,
    val currencySymbol: String = "$",
    val productUrl: String,
    val asin: String = "",
    val isParsedFromWeb: Boolean = false,
    val success: Boolean = true
)

object AmazonLinkParser {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isAmazonUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase().trim()
        return lower.contains("amazon.") ||
                lower.contains("amzn.to") ||
                lower.contains("a.co") ||
                lower.contains("amzn.in") ||
                lower.contains("amzn.eu") ||
                lower.contains("amzn.asia")
    }

    suspend fun parseUrl(inputUrl: String): ParsedAmazonProduct = withContext(Dispatchers.IO) {
        val cleanUrl = cleanInputUrl(inputUrl)
        val asin = extractAsin(cleanUrl)
        val currency = detectCurrency(cleanUrl)
        val slugTitle = extractTitleFromSlug(cleanUrl)

        var finalTitle = slugTitle
        var finalCost = 0.0
        var finalImage = if (asin.isNotEmpty()) {
            "https://images-na.ssl-images-amazon.com/images/P/$asin.01._SCLZZZZZZZ_.jpg"
        } else ""
        var parsedFromWeb = false

        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            val resolvedUrl = response.request.url.toString()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful && responseBody.isNotEmpty()) {
                // 1. Extract Title
                val webTitle = extractMetaContent(responseBody, "og:title")
                    ?: extractTagContent(responseBody, "title")
                    ?: extractById(responseBody, "productTitle")

                if (!webTitle.isNullOrBlank()) {
                    val cleaned = cleanProductTitle(webTitle)
                    if (cleaned.isNotBlank()) {
                        finalTitle = cleaned
                        parsedFromWeb = true
                    }
                }

                // 2. Extract Image
                val webImage = extractMetaContent(responseBody, "og:image")
                    ?: extractMetaContent(responseBody, "twitter:image")
                    ?: extractLandingImage(responseBody)

                if (!webImage.isNullOrBlank() && (webImage.startsWith("http://") || webImage.startsWith("https://"))) {
                    finalImage = webImage
                    parsedFromWeb = true
                }

                // 3. Extract Price
                val extractedPrice = extractPrice(responseBody)
                if (extractedPrice > 0.0) {
                    finalCost = extractedPrice
                    parsedFromWeb = true
                }
            }

            // If slug title was better or resolved URL has a better slug
            if (finalTitle.isBlank() || finalTitle.length < 4) {
                val resolvedSlug = extractTitleFromSlug(resolvedUrl)
                if (resolvedSlug.isNotBlank()) {
                    finalTitle = resolvedSlug
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AmazonLinkParser", "Failed to fetch Amazon HTML: ${e.message}")
        }

        // Final fallbacks
        if (finalTitle.isBlank()) {
            finalTitle = if (asin.isNotEmpty()) "Amazon Item ($asin)" else "Amazon Product"
        }

        if (finalImage.isBlank() && asin.isNotEmpty()) {
            finalImage = "https://images-na.ssl-images-amazon.com/images/P/$asin.01._SCLZZZZZZZ_.jpg"
        }

        ParsedAmazonProduct(
            title = finalTitle,
            cost = finalCost,
            imageUrl = finalImage,
            currencySymbol = currency,
            productUrl = cleanUrl,
            asin = asin,
            isParsedFromWeb = parsedFromWeb,
            success = true
        )
    }

    private fun cleanInputUrl(raw: String): String {
        var trimmed = raw.trim()
        // Extract URL if surrounded by other text (e.g. copied from Amazon app share sheet)
        val urlMatcher = Pattern.compile("https?://[^\\s]+").matcher(trimmed)
        if (urlMatcher.find()) {
            trimmed = urlMatcher.group(0) ?: trimmed
        }
        return trimmed
    }

    private fun extractAsin(url: String): String {
        val patterns = listOf(
            Pattern.compile("/(?:dp|gp/product|d|asin)/([A-Z0-9]{10})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[/?&]asin=([A-Z0-9]{10})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/([B0-9][A-Z0-9]{9})(?:[/?&]|$)", Pattern.CASE_INSENSITIVE)
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                val match = matcher.group(1)
                if (!match.isNullOrBlank()) {
                    return match.uppercase()
                }
            }
        }
        return ""
    }

    private fun extractTitleFromSlug(url: String): String {
        try {
            val uri = Uri.parse(url)
            val path = uri.path ?: return ""
            val segments = path.split("/").filter { it.isNotBlank() }
            for (i in segments.indices) {
                val seg = segments[i]
                if (seg.equals("dp", ignoreCase = true) && i > 0) {
                    val prev = segments[i - 1]
                    val words = prev.replace("-", " ").replace("+", " ").trim()
                    if (words.length > 2 && !words.equals("gp", ignoreCase = true)) {
                        return words.split(" ")
                            .filter { it.isNotBlank() }
                            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun cleanProductTitle(raw: String): String {
        var title = raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        // Strip common Amazon title suffixes
        val suffixes = listOf(
            ": Amazon.com: Everything Else",
            ": Amazon.com",
            ": Amazon.in",
            ": Amazon.co.uk",
            ": Amazon.de",
            ": Amazon.ca",
            " | Amazon.com",
            " | Amazon.in",
            " - Amazon.com",
            " - Amazon.in",
            "Amazon.com: ",
            "Amazon.in: "
        )
        for (s in suffixes) {
            if (title.contains(s)) {
                title = title.replace(s, "")
            }
        }
        return title.trim()
    }

    private fun extractMetaContent(html: String, propertyOrName: String): String? {
        val pattern = Pattern.compile("<meta\\s+[^>]*?(?:property|name)=[\"']$propertyOrName[\"'][^>]*?content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)
        }
        val altPattern = Pattern.compile("<meta\\s+[^>]*?content=[\"'](.*?)[\"'][^>]*?(?:property|name)=[\"']$propertyOrName[\"']", Pattern.CASE_INSENSITIVE)
        val altMatcher = altPattern.matcher(html)
        if (altMatcher.find()) {
            return altMatcher.group(1)
        }
        return null
    }

    private fun extractTagContent(html: String, tagName: String): String? {
        val pattern = Pattern.compile("<$tagName[^>]*>(.*?)</$tagName>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)?.replace(Regex("<.*?>"), "")?.trim()
        }
        return null
    }

    private fun extractById(html: String, elementId: String): String? {
        val pattern = Pattern.compile("id=[\"']$elementId[\"'][^>]*>(.*?)<", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }
        return null
    }

    private fun extractLandingImage(html: String): String? {
        val hiresPattern = Pattern.compile("data-old-hires=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val hiresMatcher = hiresPattern.matcher(html)
        if (hiresMatcher.find()) {
            return hiresMatcher.group(1)
        }
        val landingPattern = Pattern.compile("id=[\"']landingImage[\"'][^>]*?src=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val landingMatcher = landingPattern.matcher(html)
        if (landingMatcher.find()) {
            return landingMatcher.group(1)
        }
        return null
    }

    private fun extractPrice(html: String): Double {
        // 1. Check meta tags
        val metaPrice = extractMetaContent(html, "price")
            ?: extractMetaContent(html, "product:price:amount")
        if (!metaPrice.isNullOrBlank()) {
            metaPrice.replace(",", "").toDoubleOrNull()?.let { return it }
        }

        // 2. Check a-price-whole and a-price-fraction
        val wholePattern = Pattern.compile("<span\\s+class=[\"'][^\"']*?a-price-whole[^\"']*?[\"'][^>]*>([0-9,]+)<", Pattern.CASE_INSENSITIVE)
        val wholeMatcher = wholePattern.matcher(html)
        if (wholeMatcher.find()) {
            val wholePart = wholeMatcher.group(1)?.replace(",", "") ?: ""
            val fractionPattern = Pattern.compile("<span\\s+class=[\"'][^\"']*?a-price-fraction[^\"']*?[\"'][^>]*>([0-9]{2})<", Pattern.CASE_INSENSITIVE)
            val fractionMatcher = fractionPattern.matcher(html)
            val fractionPart = if (fractionMatcher.find()) fractionMatcher.group(1) ?: "00" else "00"
            val combined = "$wholePart.$fractionPart".toDoubleOrNull()
            if (combined != null && combined > 0) {
                return combined
            }
        }

        // 3. Check a-offscreen
        val offscreenPattern = Pattern.compile("<span\\s+class=[\"'][^\"']*?a-offscreen[^\"']*?[\"'][^>]*>([^<]+)<", Pattern.CASE_INSENSITIVE)
        val offscreenMatcher = offscreenPattern.matcher(html)
        while (offscreenMatcher.find()) {
            val raw = offscreenMatcher.group(1) ?: ""
            val cleanedNumber = raw.replace(Regex("[^0-9.]"), "")
            val parsed = cleanedNumber.toDoubleOrNull()
            if (parsed != null && parsed > 0.5 && parsed < 1000000) {
                return parsed
            }
        }

        // 4. Regex for price tags
        val generalPattern = Pattern.compile("[$₹€£]\\s*([0-9,]+(?:\\.[0-9]{2})?)")
        val generalMatcher = generalPattern.matcher(html)
        if (generalMatcher.find()) {
            val numStr = generalMatcher.group(1)?.replace(",", "") ?: ""
            val parsed = numStr.toDoubleOrNull()
            if (parsed != null && parsed > 0.5 && parsed < 1000000) {
                return parsed
            }
        }

        return 0.0
    }

    private fun detectCurrency(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".in") -> "₹"
            lower.contains(".co.uk") -> "£"
            lower.contains(".de") || lower.contains(".fr") || lower.contains(".it") || lower.contains(".es") -> "€"
            lower.contains(".co.jp") -> "¥"
            lower.contains(".ca") -> "C$"
            else -> "$"
        }
    }
}
