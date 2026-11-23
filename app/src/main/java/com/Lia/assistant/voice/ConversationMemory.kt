package com.Lia.assistant.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class MemoryRole { USER, ASSISTANT }
data class MemoryTurn(val role: MemoryRole, val text: String)

/** Recent voice turns; optional encrypted on-device persistence, disabled by default. */
object ConversationMemory {
    private const val MAX_TURNS = 12
    private const val MAX_RECAP_CHARS = 1600
    private const val PREFS = "shraddha_voice_memory"
    private const val ENABLED = "encrypted_history_enabled"
    private const val PAYLOAD = "history_ciphertext"
    private const val IV = "history_iv"
    private const val KEY_ALIAS = "shraddha_voice_history_aes_v1"
    private val turns = mutableListOf<MemoryTurn>()
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var persistenceEnabled = false
    @Volatile private var persistenceError: String? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            persistenceEnabled = prefs.getBoolean(ENABLED, false)
            turns.clear()
            if (persistenceEnabled) {
                val cipherText = prefs.getString(PAYLOAD, null)
                val ivText = prefs.getString(IV, null)
                persistenceError = null
                if (!cipherText.isNullOrBlank() || !ivText.isNullOrBlank()) {
                    runCatching {
                        require(!cipherText.isNullOrBlank() && !ivText.isNullOrBlank()) {
                            "Encrypted conversation history is incomplete; saved data was preserved."
                        }
                        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                            ?: error("Conversation-memory encryption key is missing; saved history was preserved.")
                        val iv = Base64.decode(ivText, Base64.NO_WRAP)
                        val encrypted = Base64.decode(cipherText, Base64.NO_WRAP)
                        try {
                            require(iv.size == 12 && encrypted.size >= 16) { "Encrypted conversation history is invalid; saved data was preserved." }
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
                            val clear = cipher.doFinal(encrypted)
                            try {
                                val array = JSONArray(String(clear, Charsets.UTF_8))
                                for (i in 0 until array.length()) {
                                    val item = array.getJSONObject(i)
                                    val role = runCatching { MemoryRole.valueOf(item.getString("role")) }.getOrNull() ?: continue
                                    val text = item.optString("text").take(1200)
                                    if (text.isNotBlank()) turns.add(MemoryTurn(role, text))
                                }
                                while (turns.size > MAX_TURNS) turns.removeAt(0)
                            } finally {
                                clear.fill(0)
                            }
                        } finally {
                            iv.fill(0)
                            encrypted.fill(0)
                        }
                    }.onFailure { error ->
                        persistenceError = error.localizedMessage ?: "Could not decrypt saved conversation history."
                        turns.clear()
                    }
                }
            }
        }
    }

    fun isPersistenceEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun persistenceError(): String? = persistenceError

    fun setPersistenceEnabled(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (enabled) {
                appContext = context.applicationContext
                persistenceEnabled = true
                persistLocked()
                check(persistenceError == null) {
                    persistenceError ?: "Could not persist encrypted conversation history."
                }
                check(prefs.edit().putBoolean(ENABLED, true).commit()) {
                    "Could not save conversation-memory preference."
                }
            } else {
                check(prefs.edit().putBoolean(ENABLED, false).remove(PAYLOAD).remove(IV).commit()) {
                    "Could not clear encrypted conversation history."
                }
                persistenceEnabled = false
                persistenceError = null
            }
        }
    }.onFailure { error ->
        synchronized(lock) {
            persistenceEnabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
            persistenceError = error.localizedMessage ?: "Could not update encrypted conversation memory."
        }
    }

    fun clearSaved(context: Context) {
        synchronized(lock) {
            turns.clear()
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PAYLOAD).remove(IV).commit()) {
                "Could not clear encrypted conversation history."
            }
            persistenceError = null
        }
    }

    fun recordTurn(role: MemoryRole, text: String) {
        val trimmed = text.trim().take(1200)
        if (trimmed.isEmpty()) return
        // Avoid retaining messages that appear to contain credentials or one-time codes.
        if (Regex("(?i)\\b(password|passcode|pass word|otp|one[- ]time code|pin|cvv|card number)\\b").containsMatchIn(trimmed)) return
        if (Regex("(?<!\\d)\\d{4,8}(?!\\d)").containsMatchIn(trimmed)) return
        synchronized(lock) {
            turns.add(MemoryTurn(role, trimmed))
            while (turns.size > MAX_TURNS) turns.removeAt(0)
            if (persistenceEnabled) persistLocked()
        }
    }

    fun recap(): String? = synchronized(lock) {
        if (turns.isEmpty()) return@synchronized null
        val fullText = turns.joinToString("\n") { (if (it.role == MemoryRole.USER) "User" else "Assistant") + ": " + it.text }.trim()
        if (fullText.isEmpty()) null else if (fullText.length > MAX_RECAP_CHARS) fullText.takeLast(MAX_RECAP_CHARS) else fullText
    }

    /** Clears only the active in-memory session. Encrypted history remains if the user opted in. */
    fun clear() { synchronized(lock) { turns.clear() } }
    fun size(): Int = synchronized(lock) { turns.size }

    private fun persistLocked() {
        val context = appContext ?: return
        val clear = JSONArray().apply {
            turns.forEach { put(JSONObject().put("role", it.role.name).put("text", it.text)) }
        }.toString().toByteArray(Charsets.UTF_8)
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption(context))
            val encrypted = cipher.doFinal(clear)
            try {
                check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .commit()) { "Could not persist encrypted conversation history." }
            } finally {
                encrypted.fill(0)
            }
        }.onSuccess {
            persistenceError = null
        }.onFailure { error ->
            persistenceError = error.localizedMessage ?: "Could not persist encrypted conversation history."
        }
        clear.fill(0)
    }

    private fun keyForEncryption(context: Context): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        require(!prefs.contains(PAYLOAD) && !prefs.contains(IV)) {
            "Conversation-memory encryption key is missing; refusing to replace it while encrypted history exists."
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
