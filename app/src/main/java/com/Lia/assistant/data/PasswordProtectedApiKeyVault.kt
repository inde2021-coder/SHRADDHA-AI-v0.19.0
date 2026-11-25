package com.Lia.assistant.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-derived API-key vault foundation.
 *
 * Provider keys are encrypted with AES-256-GCM. PBKDF2-HMAC-SHA256 derives the
 * wrapping key from the user's password; no password or plaintext key is persisted.
 * The derived key exists only for the unlocked session and is zeroed by lock().
 *
 * Callers must explicitly lock this vault on app background and must migrate existing
 * provider stores before routing production reads/writes through this class.
 */
object PasswordProtectedApiKeyVault {
    private const val PREFS = "shraddha_password_api_vault"
    private const val SALT = "vault_salt"
    private const val CHECK = "vault_check"
    private const val CHECK_IV = "vault_check_iv"
    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val CHECK_TEXT = "SHRADDHA_API_VAULT_V1"
    private val random = SecureRandom()
    @Volatile private var sessionKey: ByteArray? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun isConfigured(context: Context): Boolean = prefs(context).contains(SALT)

    @Synchronized
    fun isUnlocked(): Boolean = sessionKey != null

    @Synchronized
    fun create(context: Context, password: CharArray) {
        require(!isConfigured(context)) { "API-key vault is already configured." }
        validatePassword(password)
        val salt = ByteArray(16).also(random::nextBytes)
        val key = derive(password, salt)
        try {
            val encryptedCheck = encrypt(key, CHECK_TEXT.toByteArray(Charsets.UTF_8), "vault-check")
            check(prefs(context).edit()
                .putString(SALT, encode(salt))
                .putString(CHECK, encode(encryptedCheck.first))
                .putString(CHECK_IV, encode(encryptedCheck.second))
                .commit()) { "Could not create the API-key vault." }
            sessionKey = key.copyOf()
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }

    @Synchronized
    fun unlock(context: Context, password: CharArray): Boolean {
        val store = prefs(context)
        val saltText = store.getString(SALT, null) ?: return false
        val checkText = store.getString(CHECK, null) ?: return false
        val ivText = store.getString(CHECK_IV, null) ?: return false
        val salt = decode(saltText)
        val key = derive(password, salt)
        return try {
            val clear = decrypt(key, decode(checkText), decode(ivText), "vault-check")
            try {
                if (!MessageDigest.isEqual(clear, CHECK_TEXT.toByteArray(Charsets.UTF_8))) return false
                sessionKey?.fill(0)
                sessionKey = key.copyOf()
                true
            } finally {
                clear.fill(0)
            }
        } catch (_: Exception) {
            false
        } finally {
            salt.fill(0)
            key.fill(0)
        }
    }

    @Synchronized
    fun lock() {
        sessionKey?.fill(0)
        sessionKey = null
    }

    @Synchronized
    fun save(context: Context, provider: String, apiKey: String) {
        val id = providerId(provider)
        val key = requireUnlocked()
        val value = apiKey.trim()
        require(value.length <= 2048) { "API key is too long." }
        val store = prefs(context)
        if (value.isEmpty()) {
            check(store.edit().remove("key_$id").remove("iv_$id").commit()) {
                "Could not remove the API key."
            }
            return
        }
        val clear = value.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = encrypt(key, clear, id)
            try {
                check(store.edit()
                    .putString("key_$id", encode(encrypted.first))
                    .putString("iv_$id", encode(encrypted.second))
                    .commit()) { "Could not save the encrypted API key." }
            } finally {
                encrypted.first.fill(0)
                encrypted.second.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    fun get(context: Context, provider: String): String {
        val id = providerId(provider)
        val key = requireUnlocked()
        val store = prefs(context)
        val cipherText = store.getString("key_$id", null) ?: return ""
        val ivText = store.getString("iv_$id", null)
            ?: error("Saved $id key metadata is incomplete.")
        val clear = decrypt(key, decode(cipherText), decode(ivText), id)
        return try {
            String(clear, Charsets.UTF_8)
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    fun has(context: Context, provider: String): Boolean = get(context, provider).isNotEmpty()

    @Synchronized
    fun changePassword(context: Context, currentPassword: CharArray, newPassword: CharArray) {
        require(unlock(context, currentPassword)) { "Current vault password is incorrect." }
        validatePassword(newPassword)
        val oldKey = requireUnlocked().copyOf()
        val newSalt = ByteArray(16).also(random::nextBytes)
        val newKey = derive(newPassword, newSalt)
        val store = prefs(context)
        val editor = store.edit()
        try {
            val providers = store.all.keys.filter { it.startsWith("key_") }
                .map { it.removePrefix("key_") }
            providers.forEach { id ->
                val cipherText = store.getString("key_$id", null) ?: return@forEach
                val ivText = store.getString("iv_$id", null) ?: error("Incomplete key record: $id")
                val clear = decrypt(oldKey, decode(cipherText), decode(ivText), id)
                try {
                    val encrypted = encrypt(newKey, clear, id)
                    editor.putString("key_$id", encode(encrypted.first))
                        .putString("iv_$id", encode(encrypted.second))
                    encrypted.first.fill(0)
                    encrypted.second.fill(0)
                } finally {
                    clear.fill(0)
                }
            }
            val checkRecord = encrypt(newKey, CHECK_TEXT.toByteArray(Charsets.UTF_8), "vault-check")
            editor.putString(SALT, encode(newSalt))
                .putString(CHECK, encode(checkRecord.first))
                .putString(CHECK_IV, encode(checkRecord.second))
            check(editor.commit()) { "Could not update the vault password." }
            sessionKey?.fill(0)
            sessionKey = newKey.copyOf()
            checkRecord.first.fill(0)
            checkRecord.second.fill(0)
        } finally {
            oldKey.fill(0)
            newKey.fill(0)
            newSalt.fill(0)
        }
    }

    /** Forgotten passwords cannot be recovered; reset permanently deletes every stored key. */
    @Synchronized
    fun resetAndDeleteAllKeys(context: Context) {
        lock()
        check(prefs(context).edit().clear().commit()) { "Could not reset the API-key vault." }
    }

    private fun requireUnlocked(): ByteArray =
        sessionKey ?: error("API-key vault is locked. Unlock it in AI API Key settings.")

    private fun validatePassword(password: CharArray) {
        require(password.size >= 10) { "Use a vault password with at least 10 characters." }
        require(password.any { it.isLetter() } && password.any { it.isDigit() }) {
            "Vault password must include at least one letter and one number."
        }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encrypt(key: ByteArray, clear: ByteArray, aad: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(clear) to cipher.iv
    }

    private fun decrypt(key: ByteArray, encrypted: ByteArray, iv: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted)
    }

    private fun providerId(provider: String): String {
        val id = provider.trim().uppercase()
        require(id.matches(Regex("[A-Z0-9_]{2,32}"))) { "Invalid provider identifier." }
        return id
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
