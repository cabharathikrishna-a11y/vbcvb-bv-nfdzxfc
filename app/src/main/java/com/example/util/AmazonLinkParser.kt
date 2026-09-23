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
    val currencySymbol: String = "₹",
    val productUrl: String,
    val asin: String = "",
    val isParsedFromWeb: Boolean = false,
    val success: Boolean = true
)

object AmazonLinkParser {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
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
        val initialAsin = extractAsin(cleanUrl)
        val slugTitle = extractTitleFromSlug(cleanUrl)

        var finalTitle = slugTitle
        var finalCost = 0.0
        var finalAsin = initialAsin
        var finalImage = if (initialAsin.isNotEmpty()) {
            "https://images-na.ssl-images-amazon.com/images/P/$initialAsin.01._SCLZZZZZZZ_.jpg"
        } else ""
        var parsedFromWeb = false
        var targetProductUrl = cleanUrl

        // 1. If ASIN is available and input is not already amazon.in, attempt to fetch from Amazon India first
        var indiaSuccess = false
        if (initialAsin.isNotEmpty() && !cleanUrl.lowercase().contains("amazon.in") && !cleanUrl.lowercase().contains("amzn.in")) {
            val amazonInUrl = "https://www.amazon.in/dp/$initialAsin"
            val inResult = fetchAndParseHtml(amazonInUrl, forceIndianCurrency = true)
            if (inResult.success && (inResult.cost > 0.0 || inResult.title.isNotBlank())) {
                indiaSuccess = true
                if (inResult.title.isNotBlank()) finalTitle = inResult.title
                if (inResult.cost > 0.0) finalCost = inResult.cost
                if (inResult.imageUrl.isNotBlank()) finalImage = inResult.imageUrl
                parsedFromWeb = true
                targetProductUrl = amazonInUrl
            }
        }

        // 2. If not already successfully fetched from Amazon India, fetch the given URL directly
        if (!indiaSuccess) {
            val isIndianSource = cleanUrl.lowercase().contains("amazon.in") || cleanUrl.lowercase().contains("amzn.in")
            val webResult = fetchAndParseHtml(cleanUrl, forceIndianCurrency = isIndianSource)
            if (webResult.success) {
                if (webResult.title.isNotBlank()) finalTitle = webResult.title
                if (webResult.cost > 0.0) finalCost = webResult.cost
                if (webResult.imageUrl.isNotBlank()) finalImage = webResult.imageUrl
                if (webResult.asin.isNotBlank()) finalAsin = webResult.asin
                parsedFromWeb = true
                targetProductUrl = webResult.resolvedUrl.ifBlank { cleanUrl }

                // If resolved URL found an ASIN and original was not Indian and we still don't have Indian price:
                if (!isIndianSource && finalAsin.isNotEmpty() && finalCost <= 0.0) {
                    val fallbackInUrl = "https://www.amazon.in/dp/$finalAsin"
                    val inResult = fetchAndParseHtml(fallbackInUrl, forceIndianCurrency = true)
                    if (inResult.success && inResult.cost > 0.0) {
                        finalCost = inResult.cost
                        if (finalTitle.isBlank()) finalTitle = inResult.title
                        if (finalImage.isBlank()) finalImage = inResult.imageUrl
                    }
                }
            }
        }

        // Final fallbacks
        if (finalTitle.isBlank()) {
            finalTitle = if (finalAsin.isNotEmpty()) "Amazon Item ($finalAsin)" else "Amazon Product"
        }

        if (finalImage.isBlank() && finalAsin.isNotEmpty()) {
            finalImage = "https://images-na.ssl-images-amazon.com/images/P/$finalAsin.01._SCLZZZZZZZ_.jpg"
        }

        ParsedAmazonProduct(
            title = finalTitle,
            cost = finalCost,
            imageUrl = finalImage,
            currencySymbol = "₹",
            productUrl = targetProductUrl,
            asin = finalAsin,
            isParsedFromWeb = parsedFromWeb,
            success = true
        )
    }

    private data class HtmlParseResult(
        val title: String = "",
        val cost: Double = 0.0,
        val imageUrl: String = "",
        val asin: String = "",
        val resolvedUrl: String = "",
        val success: Boolean = false
    )

    private fun fetchAndParseHtml(url: String, forceIndianCurrency: Boolean): HtmlParseResult {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("DNT", "1")
                .header("Upgrade-Insecure-Requests", "1")
                .build()

            val response = httpClient.newCall(request).execute()
            val resolvedUrl = response.request.url.toString()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful && responseBody.isNotEmpty()) {
                // Avoid bot-detection captcha pages
                if (responseBody.contains("Type the characters you see in this image") ||
                    responseBody.contains("Enter the characters you see below")
                ) {
                    return HtmlParseResult(resolvedUrl = resolvedUrl, success = false)
                }

                // 1. Title
                val webTitle = extractMetaContent(responseBody, "og:title")
                    ?: extractTagContent(responseBody, "title")
                    ?: extractById(responseBody, "productTitle")
                var parsedTitle = ""
                if (!webTitle.isNullOrBlank()) {
                    parsedTitle = cleanProductTitle(webTitle)
                }

                // 2. Image
                val webImage = extractMetaContent(responseBody, "og:image")
                    ?: extractMetaContent(responseBody, "twitter:image")
                    ?: extractLandingImage(responseBody)
                val parsedImage = if (!webImage.isNullOrBlank() && (webImage.startsWith("http://") || webImage.startsWith("https://"))) {
                    webImage
                } else ""

                // 3. Price (extract and ensure converted to INR)
                val priceResult = extractPrice(responseBody, url = resolvedUrl, forceIndian = forceIndianCurrency)

                val asin = extractAsin(resolvedUrl).ifBlank { extractAsin(url) }

                return HtmlParseResult(
                    title = parsedTitle,
                    cost = priceResult,
                    imageUrl = parsedImage,
                    asin = asin,
                    resolvedUrl = resolvedUrl,
                    success = true
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AmazonLinkParser", "Failed to fetch Amazon HTML for $url: ${e.message}")
        }
        return HtmlParseResult()
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

    private fun extractPrice(html: String, url: String = "", forceIndian: Boolean = false): Double {
        var detectedRawPrice = 0.0
        var detectedCurrency = if (forceIndian || url.contains(".in") || html.contains("₹") || html.contains("&#8377;") || html.contains("INR") || html.contains("priceCurrency\" content=\"INR\"")) "INR" else ""

        // 1. Check primary priceToPay / apexPriceToPay containers first (most reliable on Amazon)
        val containerPattern = Pattern.compile(
            "<(?:span|div)[^>]*?class=[\"'][^\"']*?(?:priceToPay|apexPriceToPay|reinventPricePriceToPayMargin|corePriceDisplay)[^\"']*?[\"'][^>]*>(.*?)</(?:span|div)>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        val containerMatcher = containerPattern.matcher(html)
        if (containerMatcher.find()) {
            val block = containerMatcher.group(1) ?: ""
            // Look for a-offscreen inside this container
            val offscreenInBlock = Pattern.compile("<span\\s+class=[\"'][^\"']*?a-offscreen[^\"']*?[\"'][^>]*>([^<]+)</span>", Pattern.CASE_INSENSITIVE).matcher(block)
            if (offscreenInBlock.find()) {
                val raw = offscreenInBlock.group(1) ?: ""
                val isRupee = raw.contains("₹") || raw.contains("Rs") || raw.contains("INR")
                if (isRupee) detectedCurrency = "INR"
                val cleaned = raw.replace(Regex("[^0-9.]"), "")
                val parsed = cleaned.toDoubleOrNull()
                if (parsed != null && parsed > 0.0) {
                    detectedRawPrice = parsed
                }
            }
        }

        // 2. Check JSON-LD structured data (Product Schema)
        if (detectedRawPrice <= 0.0) {
            val jsonLdPattern = Pattern.compile("<script[^>]*?type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val jsonLdMatcher = jsonLdPattern.matcher(html)
            while (jsonLdMatcher.find()) {
                val json = jsonLdMatcher.group(1) ?: ""
                if (json.contains("\"price\"")) {
                    val priceMatch = Pattern.compile("\"price\"\\s*:\\s*[\"']?([0-9]+(?:\\.[0-9]{1,2})?)[\"']?", Pattern.CASE_INSENSITIVE).matcher(json)
                    if (priceMatch.find()) {
                        val parsed = priceMatch.group(1)?.toDoubleOrNull()
                        if (parsed != null && parsed > 0.0) {
                            detectedRawPrice = parsed
                            val currMatch = Pattern.compile("\"priceCurrency\"\\s*:\\s*[\"']([A-Z]{3})[\"']?", Pattern.CASE_INSENSITIVE).matcher(json)
                            if (currMatch.find()) {
                                val curr = currMatch.group(1) ?: ""
                                if (curr.isNotBlank()) detectedCurrency = curr
                            }
                            break
                        }
                    }
                }
            }
        }

        // 3. Check meta tags
        if (detectedRawPrice <= 0.0) {
            val metaPrice = extractMetaContent(html, "price")
                ?: extractMetaContent(html, "product:price:amount")
            if (!metaPrice.isNullOrBlank()) {
                metaPrice.replace(",", "").toDoubleOrNull()?.let {
                    detectedRawPrice = it
                    val metaCurr = extractMetaContent(html, "currency")
                        ?: extractMetaContent(html, "product:price:currency")
                    if (!metaCurr.isNullOrBlank()) detectedCurrency = metaCurr
                }
            }
        }

        // 4. Check a-price-whole and a-price-fraction
        if (detectedRawPrice <= 0.0) {
            val wholePattern = Pattern.compile("<span\\s+class=[\"'][^\"']*?a-price-whole[^\"']*?[\"'][^>]*>(.*?)</span>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val wholeMatcher = wholePattern.matcher(html)
            if (wholeMatcher.find()) {
                val wholePart = wholeMatcher.group(1)?.replace(Regex("<[^>]+>"), "")?.replace(",", "")?.replace(".", "")?.trim() ?: ""
                val fractionPattern = Pattern.compile("<span\\s+class=[\"'][^\"']*?a-price-fraction[^\"']*?[\"'][^>]*>([0-9]{1,2})<", Pattern.CASE_INSENSITIVE)
                val fractionMatcher = fractionPattern.matcher(html)
                val fractionPart = if (fractionMatcher.find()) fractionMatcher.group(1) ?: "00" else "00"
                val combined = "$wholePart.$fractionPart".toDoubleOrNull()
                if (combined != null && combined > 0.0) {
                    detectedRawPrice = combined
                }
            }
        }

        // 5. Check a-offscreen across document
        if (detectedRawPrice <= 0.0) {
            val offscreenPattern = Pattern.compile("<span\\s+class=[\"'][^\"']*?a-offscreen[^\"']*?[\"'][^>]*>([^<]+)</span>", Pattern.CASE_INSENSITIVE)
            val offscreenMatcher = offscreenPattern.matcher(html)
            while (offscreenMatcher.find()) {
                val raw = offscreenMatcher.group(1) ?: ""
                if (raw.contains("₹") || raw.contains("Rs") || raw.contains("INR")) {
                    detectedCurrency = "INR"
                } else if (raw.contains("$")) {
                    if (detectedCurrency.isBlank()) detectedCurrency = "USD"
                } else if (raw.contains("£")) {
                    if (detectedCurrency.isBlank()) detectedCurrency = "GBP"
                } else if (raw.contains("€")) {
                    if (detectedCurrency.isBlank()) detectedCurrency = "EUR"
                }

                val cleanedNumber = raw.replace(Regex("[^0-9.]"), "")
                val parsed = cleanedNumber.toDoubleOrNull()
                if (parsed != null && parsed > 0.5 && parsed < 10000000) {
                    detectedRawPrice = parsed
                    break
                }
            }
        }

        // 6. Regex for currency symbol + price
        if (detectedRawPrice <= 0.0) {
            val generalPattern = Pattern.compile("([$₹€£]|Rs\\.?)\\s*([0-9,]+(?:\\.[0-9]{2})?)", Pattern.CASE_INSENSITIVE)
            val generalMatcher = generalPattern.matcher(html)
            if (generalMatcher.find()) {
                val sym = generalMatcher.group(1) ?: ""
                if (sym.contains("₹") || sym.contains("Rs", ignoreCase = true)) {
                    detectedCurrency = "INR"
                } else if (sym.contains("$")) {
                    detectedCurrency = "USD"
                }
                val numStr = generalMatcher.group(2)?.replace(",", "") ?: ""
                val parsed = numStr.toDoubleOrNull()
                if (parsed != null && parsed > 0.5 && parsed < 10000000) {
                    detectedRawPrice = parsed
                }
            }
        }

        if (detectedRawPrice <= 0.0) {
            return 0.0
        }

        // Convert to Indian Rupees if price was detected in a foreign currency (e.g. from amazon.com)
        return convertToIndianRupees(detectedRawPrice, detectedCurrency, url)
    }

    private fun convertToIndianRupees(amount: Double, currency: String, url: String): Double {
        val currUpper = currency.uppercase().trim()
        val isAlreadyInr = currUpper == "INR" || currUpper == "₹" || currUpper == "RS" || url.contains(".in")
        if (isAlreadyInr) {
            return Math.round(amount * 100.0) / 100.0
        }

        // Conversion exchange rates to INR
        val rate = when {
            currUpper.contains("USD") || currUpper.contains("$") -> 86.50
            currUpper.contains("GBP") || currUpper.contains("£") -> 112.00
            currUpper.contains("EUR") || currUpper.contains("€") -> 94.00
            currUpper.contains("CAD") || currUpper.contains("C$") -> 63.50
            currUpper.contains("AUD") || currUpper.contains("A$") -> 56.50
            currUpper.contains("JPY") || currUpper.contains("¥") -> 0.58
            url.contains(".com") -> 86.50 // Default USD conversion for amazon.com
            url.contains(".co.uk") -> 112.00
            url.contains(".de") || url.contains(".fr") -> 94.00
            else -> 86.50
        }

        val inrAmount = amount * rate
        return Math.round(inrAmount * 100.0) / 100.0
    }

    fun detectCurrency(url: String = ""): String {
        return "₹"
    }
}
