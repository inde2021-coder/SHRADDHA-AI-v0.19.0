package com.Lia.assistant.action

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

sealed class ContactLookupResult {
    data class Found(val displayName: String, val phoneNumber: String) : ContactLookupResult()
    data class Ambiguous(val candidates: List<Pair<String, String>>) : ContactLookupResult()
    object NoPermission : ContactLookupResult()
    object NotFound : ContactLookupResult()
}

object ContactResolver {

    fun isDirectPhoneNumber(query: String): Boolean {
        val trimmed = query.trim()
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length < 5) return false

        // Check if query only contains digits and standard phone symbols
        val validChars = setOf('+', '-', ' ', '(', ')', '.')
        return trimmed.all { it.isDigit() || validChars.contains(it) }
    }

    fun lookup(context: Context, query: String): ContactLookupResult {
        val q = query.trim()
        if (q.isEmpty()) return ContactLookupResult.NotFound

        if (isDirectPhoneNumber(q)) {
            return ContactLookupResult.Found(displayName = q, phoneNumber = q)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ContactLookupResult.NoPermission
        }

        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$q%")
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT 5"

        val candidates = mutableListOf<Pair<String, String>>()

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: ""
                    val number = cursor.getString(numberIdx) ?: ""
                    if (name.isNotBlank() && number.isNotBlank()) {
                        candidates.add(Pair(name, number))
                    }
                }
            }
        } catch (_: Exception) {
            return ContactLookupResult.NotFound
        }

        if (candidates.isEmpty()) {
            return ContactLookupResult.NotFound
        }

        // Never place a call based only on the first fuzzy match. Exact name wins
        // only when it resolves to one distinct number; otherwise ask the user.
        val qLower = q.lowercase().trim()
        val distinct = candidates.distinctBy { it.second }
        val exact = distinct.filter { it.first.lowercase().trim() == qLower }
        if (exact.size == 1) {
            val best = exact.first()
            return ContactLookupResult.Found(best.first, best.second)
        }
        if (exact.size > 1) return ContactLookupResult.Ambiguous(exact.take(5))

        val prefix = distinct.filter { it.first.lowercase().trim().startsWith(qLower) }
        if (prefix.size == 1) {
            val best = prefix.first()
            return ContactLookupResult.Found(best.first, best.second)
        }
        if (prefix.size > 1) return ContactLookupResult.Ambiguous(prefix.take(5))

        val contains = distinct.filter { it.first.lowercase().contains(qLower) }
        return when (contains.size) {
            0 -> ContactLookupResult.NotFound
            1 -> ContactLookupResult.Found(contains.first().first, contains.first().second)
            else -> ContactLookupResult.Ambiguous(contains.take(5))
        }
    }
}
