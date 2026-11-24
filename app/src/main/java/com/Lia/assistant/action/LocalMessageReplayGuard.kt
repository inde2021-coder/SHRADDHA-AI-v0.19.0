package com.Lia.assistant.action

import android.content.Context
import java.security.MessageDigest

/**
 * Local SMS/WhatsApp replay protection. Only SHA-256 fingerprints and expiry
 * timestamps are persisted; message text and phone numbers are never stored.
 */
object LocalMessageReplayGuard {
    private const val PREFS = "shraddha_local_message_replay"
    private const val PREFIX = "request_"
    private const val TTL_MS = 24L * 60L * 60L * 1000L
    private const val MAX_RECORDS = 512
    private val lock = Any()

    data class Claim(val accepted: Boolean, val reason: String = "")

    internal fun isValidRequestId(requestId: String): Boolean =
        requestId.matches(Regex("^[A-Za-z0-9_-]{16,80}$"))

    fun fingerprint(channel: String, recipient: String, message: String): String =
        sha256("$channel|$recipient|$message")

    fun claim(context: Context, requestId: String, fingerprint: String, now: Long = System.currentTimeMillis()): Claim {
        if (!isValidRequestId(requestId) ||
            !fingerprint.matches(Regex("^[a-f0-9]{64}$"))) return Claim(false, "invalid_request")
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val live = prefs.all.filterKeys { it.startsWith(PREFIX) }.filterValues { value ->
                val parts = value as? String ?: return@filterValues false
                val expiry = parts.substringAfter('|', "").toLongOrNull() ?: 0L
                expiry > now
            }
            prefs.all.keys.filter { it.startsWith(PREFIX) && it !in live.keys }.forEach(editor::remove)
            if (live.size >= MAX_RECORDS) {
                if (!editor.commit()) return Claim(false, "storage_unavailable")
                return Claim(false, "replay_store_full")
            }
            val key = PREFIX + sha256(requestId)
            val existing = live[key] as? String
            if (existing != null) {
                val priorFingerprint = existing.substringBefore('|')
                return Claim(false, if (priorFingerprint == fingerprint) "already_submitted" else "request_id_conflict")
            }
            editor.putString(key, "$fingerprint|${now + TTL_MS}")
            if (!editor.commit()) return Claim(false, "storage_unavailable")
            return Claim(true)
        }
    }

    fun release(context: Context, requestId: String, fingerprint: String): Boolean {
        if (!requestId.matches(Regex("^[A-Za-z0-9_-]{16,80}$")) ||
            !fingerprint.matches(Regex("^[a-f0-9]{64}$"))) return false
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = PREFIX + sha256(requestId)
            val stored = prefs.getString(key, null) ?: return false
            if (stored.substringBefore('|') != fingerprint) return false
            return prefs.edit().remove(key).commit()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
