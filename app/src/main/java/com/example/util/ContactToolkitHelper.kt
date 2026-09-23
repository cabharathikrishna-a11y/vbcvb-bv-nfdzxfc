package com.example.util

import com.example.data.Contact

/**
 * Reason or criterion by which two contacts are flagged as duplicates.
 */
enum class ContactDuplicateReason {
    SAME_PHONE,
    SAME_NAME,
    SAME_INSTAGRAM,
    EXACT_MATCH
}

/**
 * Represents a detected duplicate or conflicting pair/group of contacts.
 */
data class ContactDuplicateGroup(
    val id: String, // Unique identifier for the conflict group
    val primaryContact: Contact,
    val duplicateContact: Contact,
    val matchReasons: List<ContactDuplicateReason>,
    val isExactMatch: Boolean,
    val conflictingFields: List<String>
)

object ContactToolkitHelper {

    /**
     * Extracts normalized phone digits (only digits, ignoring spaces, dashes, +, parentheses).
     */
    fun normalizePhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        val digits = phone.filter { it.isDigit() }
        // If it starts with country code like 91 or 1, and length > 10, normalize to last 10 digits for loose matching if appropriate,
        // or compare full digits. For accuracy, let's keep all digits if length <= 10, or last 10 digits if > 10 digits.
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    /**
     * Extracts normalized full name (lowercase, collapsed spaces, trimmed).
     */
    fun normalizeName(contact: Contact): String {
        val parts = listOf(contact.firstName, contact.middleName, contact.lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return parts.joinToString(" ").lowercase().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Extracts normalized Instagram ID / handle from contact additionalFieldsJson.
     */
    fun extractInstagramHandle(additionalFieldsJson: String?): String {
        if (additionalFieldsJson.isNullOrBlank()) return ""
        try {
            val customFields = ContactSocialHelper.parseCustomFields(additionalFieldsJson)
            for (field in customFields) {
                val recognized = ContactSocialHelper.classifyField(field.first, field.second)
                if (recognized.type == ContactSocialHelper.CustomFieldType.INSTAGRAM_ID ||
                    recognized.type == ContactSocialHelper.CustomFieldType.INSTAGRAM_LINK
                ) {
                    val handle = recognized.normalizedHandle
                    if (handle.isNotEmpty()) {
                        return handle
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
        return ""
    }

    /**
     * Determines whether two contacts are identical in all fields (ignoring system/local auto-generated IDs).
     */
    fun isExactSameDetails(c1: Contact, c2: Contact): Boolean {
        // Compare all significant data fields
        val sameFirstName = c1.firstName.trim().equals(c2.firstName.trim(), ignoreCase = true)
        val sameMiddleName = c1.middleName.trim().equals(c2.middleName.trim(), ignoreCase = true)
        val sameLastName = c1.lastName.trim().equals(c2.lastName.trim(), ignoreCase = true)
        val samePhone = normalizePhone(c1.phone) == normalizePhone(c2.phone)
        val sameEmail = c1.email.trim().equals(c2.email.trim(), ignoreCase = true)
        val sameAddress = c1.address.trim().equals(c2.address.trim(), ignoreCase = true)
        val sameJob = c1.jobTitle.trim().equals(c2.jobTitle.trim(), ignoreCase = true)
        val sameDob = c1.dobString.trim() == c2.dobString.trim()
        val sameAnniversary = c1.anniversaryString.trim() == c2.anniversaryString.trim()
        val sameFolder = c1.folder.trim().equals(c2.folder.trim(), ignoreCase = true)
        val sameAdditionalFields = c1.additionalFieldsJson.trim() == c2.additionalFieldsJson.trim()
        val sameAdditionalDates = c1.additionalDatesJson.trim() == c2.additionalDatesJson.trim()

        return sameFirstName && sameMiddleName && sameLastName && samePhone && sameEmail &&
                sameAddress && sameJob && sameDob && sameAnniversary && sameFolder &&
                sameAdditionalFields && sameAdditionalDates
    }

    /**
     * Identifies differences between two contacts for display in the manual resolution UI.
     */
    fun findDifferences(c1: Contact, c2: Contact): List<String> {
        val diffs = mutableListOf<String>()
        if (!c1.firstName.trim().equals(c2.firstName.trim(), ignoreCase = true) ||
            !c1.lastName.trim().equals(c2.lastName.trim(), ignoreCase = true)) {
            diffs.add("Different Names: \"${c1.firstName} ${c1.lastName}\" vs \"${c2.firstName} ${c2.lastName}\"")
        }
        if (normalizePhone(c1.phone) != normalizePhone(c2.phone)) {
            diffs.add("Different Phone: \"${c1.phone}\" vs \"${c2.phone}\"")
        }
        if (!c1.email.trim().equals(c2.email.trim(), ignoreCase = true)) {
            diffs.add("Different Email: \"${c1.email}\" vs \"${c2.email}\"")
        }
        val ig1 = extractInstagramHandle(c1.additionalFieldsJson)
        val ig2 = extractInstagramHandle(c2.additionalFieldsJson)
        if (ig1.isNotEmpty() && ig2.isNotEmpty() && ig1 != ig2) {
            diffs.add("Different Instagram: @$ig1 vs @$ig2")
        }
        if (c1.jobTitle.trim() != c2.jobTitle.trim() && (c1.jobTitle.isNotEmpty() || c2.jobTitle.isNotEmpty())) {
            diffs.add("Different Job: \"${c1.jobTitle}\" vs \"${c2.jobTitle}\"")
        }
        if (c1.address.trim() != c2.address.trim() && (c1.address.isNotEmpty() || c2.address.isNotEmpty())) {
            diffs.add("Different Address: \"${c1.address}\" vs \"${c2.address}\"")
        }
        if (c1.dobString.trim() != c2.dobString.trim() && (c1.dobString.isNotEmpty() || c2.dobString.isNotEmpty())) {
            diffs.add("Different Birthday: \"${c1.dobString}\" vs \"${c2.dobString}\"")
        }
        return diffs
    }

    /**
     * Finds pairs of duplicate contacts matching on phone number, name, or Instagram ID.
     */
    fun scanForDuplicates(contacts: List<Contact>): List<ContactDuplicateGroup> {
        val results = mutableListOf<ContactDuplicateGroup>()
        val processedPairs = mutableSetOf<Pair<Int, Int>>()

        for (i in 0 until contacts.size) {
            val c1 = contacts[i]
            val phone1 = normalizePhone(c1.phone)
            val name1 = normalizeName(c1)
            val insta1 = extractInstagramHandle(c1.additionalFieldsJson)

            for (j in i + 1 until contacts.size) {
                val c2 = contacts[j]
                val pairKey = Pair(minOf(c1.id, c2.id), maxOf(c1.id, c2.id))
                if (processedPairs.contains(pairKey)) continue

                val phone2 = normalizePhone(c2.phone)
                val name2 = normalizeName(c2)
                val insta2 = extractInstagramHandle(c2.additionalFieldsJson)

                val reasons = mutableListOf<ContactDuplicateReason>()

                val phoneMatch = phone1.isNotEmpty() && phone2.isNotEmpty() && phone1 == phone2
                val nameMatch = name1.isNotEmpty() && name2.isNotEmpty() && name1 == name2
                val instaMatch = insta1.isNotEmpty() && insta2.isNotEmpty() && insta1 == insta2

                if (phoneMatch) reasons.add(ContactDuplicateReason.SAME_PHONE)
                if (nameMatch) reasons.add(ContactDuplicateReason.SAME_NAME)
                if (instaMatch) reasons.add(ContactDuplicateReason.SAME_INSTAGRAM)

                if (reasons.isNotEmpty()) {
                    val isExact = isExactSameDetails(c1, c2)
                    if (isExact) {
                        reasons.add(ContactDuplicateReason.EXACT_MATCH)
                    }

                    // Choose default primary contact:
                    // If one has googleContactId and the other doesn't, keep the google one as primary!
                    val (primary, duplicate) = when {
                        !c1.googleContactId.isNullOrEmpty() && c2.googleContactId.isNullOrEmpty() -> Pair(c1, c2)
                        c1.googleContactId.isNullOrEmpty() && !c2.googleContactId.isNullOrEmpty() -> Pair(c2, c1)
                        !c1.photoUri.isNullOrEmpty() && c2.photoUri.isNullOrEmpty() -> Pair(c1, c2)
                        c1.photoUri.isNullOrEmpty() && !c2.photoUri.isNullOrEmpty() -> Pair(c2, c1)
                        else -> Pair(c1, c2)
                    }

                    results.add(
                        ContactDuplicateGroup(
                            id = "${primary.id}_${duplicate.id}",
                            primaryContact = primary,
                            duplicateContact = duplicate,
                            matchReasons = reasons.distinct(),
                            isExactMatch = isExact,
                            conflictingFields = findDifferences(primary, duplicate)
                        )
                    )
                    processedPairs.add(pairKey)
                }
            }
        }

        return results
    }
}
