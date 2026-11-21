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
        val validChars = setOf('+', '-', ' ', '(', ')', '.')
        return trimmed.all { it.isDigit() || it in validChars }
    }

    /** Pure candidate resolver, separately testable without Android Contacts. */
    fun resolveCandidates(query: String, candidates: List<Pair<String, String>>): ContactLookupResult {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return ContactLookupResult.NotFound
        val distinct = candidates.filter { it.first.isNotBlank() && it.second.isNotBlank() }.distinctBy { it.second }
        val exact = distinct.filter { it.first.trim().lowercase() == normalized }
        if (exact.size == 1) return ContactLookupResult.Found(exact[0].first, exact[0].second)
        if (exact.size > 1) return ContactLookupResult.Ambiguous(exact.take(5))

        val prefix = distinct.filter { it.first.trim().lowercase().startsWith(normalized) }
        if (prefix.size == 1) return ContactLookupResult.Found(prefix[0].first, prefix[0].second)
        if (prefix.size > 1) return ContactLookupResult.Ambiguous(prefix.take(5))

        val contains = distinct.filter { it.first.lowercase().contains(normalized) }
        return when (contains.size) {
            0 -> ContactLookupResult.NotFound
            1 -> ContactLookupResult.Found(contains[0].first, contains[0].second)
            else -> ContactLookupResult.Ambiguous(contains.take(5))
        }
    }

    fun lookup(context: Context, query: String): ContactLookupResult {
        val q = query.trim()
        if (q.isEmpty()) return ContactLookupResult.NotFound
        if (isDirectPhoneNumber(q)) return ContactLookupResult.Found(q, q)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ContactLookupResult.NoPermission
        }

        val candidates = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$q%")
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT 20"
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex < 0 || numberIndex < 0) return ContactLookupResult.NotFound
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val number = cursor.getString(numberIndex).orEmpty()
                    if (name.isNotBlank() && number.isNotBlank()) candidates.add(name to number)
                }
            }
        } catch (_: Exception) {
            return ContactLookupResult.NotFound
        }
        return resolveCandidates(q, candidates)
    }
}
