package com.voxyn.communication

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import com.voxyn.utils.VoxynLogger

/**
 * ContactStore abstraction — allows JVM tests to fake contact data.
 */
interface ContactStore {
    /** Returns list of (displayName, phoneNumber) for all contacts with a phone. */
    fun getAllContactsWithPhone(): List<Pair<String, String>>
}

/**
 * Production implementation using Android ContactsContract.
 */
class AndroidContactStore(private val contentResolver: ContentResolver) : ContactStore {

    override fun getAllContactsWithPhone(): List<Pair<String, String>> {
        val contacts = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        ) ?: return contacts

        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: ""
                val number = it.getString(numberIdx) ?: ""
                if (name.isNotBlank() && number.isNotBlank()) {
                    contacts.add(normalizeName(name) to normalizeNumber(number))
                }
            }
        }
        return contacts
    }

    private fun normalizeName(name: String): String =
        name.trim().lowercase()

    private fun normalizeNumber(number: String): String =
        number.replace(Regex("[^0-9+]"), "")
}

/**
 * Resolution outcome sealed hierarchy.
 */
sealed class ContactResolution {
    data class Unique(val displayName: String, val phoneNumber: String) : ContactResolution()
    data class Ambiguous(val candidates: List<Pair<String, String>>) : ContactResolution()
    object NotFound : ContactResolution()
}

/**
 * Resolves natural-language contact names to phone numbers.
 * - Static aliases (mom, dad, etc.)
 * - Exact match on normalized display name
 * - Fuzzy match (contains) as fallback
 * - Raw digit strings pass through as direct numbers
 */
class ContactResolver(
    private val store: ContactStore,
    private val aliases: Map<String, String> = DEFAULT_ALIASES
) {

    companion object {
        private const val TAG = "CONTACT"
        private val DEFAULT_ALIASES = mapOf(
            "mom" to "Mom", "mum" to "Mom", "mommy" to "Mom",
            "dad" to "Dad", "daddy" to "Dad", "papa" to "Dad", "father" to "Dad",
            "brother" to "Brother", "bro" to "Brother",
            "sister" to "Sister", "sis" to "Sister",
            "wife" to "Wife", "husband" to "Husband",
            "son" to "Son", "daughter" to "Daughter"
        )
    }

    fun resolve(rawQuery: String): ContactResolution {
        val q = rawQuery.trim()
        VoxynLogger.d("Resolving contact: '$q'", TAG)

        // 1) Alias shortcut
        aliases[q.lowercase()]?.let { aliasTarget ->
            return resolveExact(aliasTarget)
        }

        // 2) Raw digit string → treat as phone number directly
        val digitsOnly = q.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7 && digitsOnly.all { it.isDigit() || it == '+' }) {
            VoxynLogger.d("Direct number: $digitsOnly", TAG)
            return ContactResolution.Unique(q, digitsOnly)
        }

        // 3) Exact match on normalized display name
        val exact = resolveExact(q)
        if (exact !is ContactResolution.NotFound) return exact

        // 4) Fuzzy: contains (case-insensitive)
        val fuzzy = resolveFuzzy(q)
        return fuzzy
    }

    private fun resolveExact(query: String): ContactResolution {
        val contacts = store.getAllContactsWithPhone()
        val normalizedQuery = query.trim().lowercase()
        val matches = contacts.filter { it.first == normalizedQuery }
        return when (matches.size) {
            0 -> ContactResolution.NotFound
            1 -> ContactResolution.Unique(matches[0].first, matches[0].second)
            else -> ContactResolution.Ambiguous(matches)
        }
    }

    private fun resolveFuzzy(query: String): ContactResolution {
        val contacts = store.getAllContactsWithPhone()
        val normalizedQuery = query.trim().lowercase()
        val matches = contacts.filter { it.first.contains(normalizedQuery) }
        return when (matches.size) {
            0 -> ContactResolution.NotFound
            1 -> ContactResolution.Unique(matches[0].first, matches[0].second)
            else -> ContactResolution.Ambiguous(matches)
        }
    }
}
