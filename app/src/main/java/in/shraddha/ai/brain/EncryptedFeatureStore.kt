package `in`.shraddha.ai.brain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted, device-local JSON store for child and person profiles. */
class EncryptedFeatureStore(context: Context) {
    private val prefs = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
    private val alias = "shraddha_feature_store_aes_v1"
    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey
    }
    private fun key(): SecretKey {
        existingKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    @Synchronized fun put(name: String, value: String): Result<Unit> = runCatching {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        require(value.length <= 100_000) { "Data too large" }
        val clear = value.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(clear)
            try {
                val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                check(prefs.edit().putString(name, payload).commit()) { "Save failed" }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }
    @Synchronized fun get(name: String): Result<String> = runCatching {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        val payload = prefs.getString(name, null) ?: return@runCatching ""
        require(payload.length <= 600_000) { "Encrypted feature data is too large" }
        val parts = payload.split('.', limit = 2)
        require(parts.size == 2) { "Encrypted feature data format is invalid" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == 12 && encrypted.size >= 16) { "Encrypted feature data parameters are invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val existingKey = existingKey() ?: error("Encrypted feature-store key is missing; refusing to create a new key for decryption")
        cipher.init(Cipher.DECRYPT_MODE, existingKey, GCMParameterSpec(128, iv))
        val clear = cipher.doFinal(encrypted)
        try {
            String(clear, Charsets.UTF_8)
        } finally {
            clear.fill(0)
            encrypted.fill(0)
        }
    }

    /** Encrypt and commit a related set of feature records in one SharedPreferences transaction. */
    @Synchronized fun putAll(values: Map<String, String>): Result<Unit> = runCatching {
        require(values.isNotEmpty()) { "At least one record is required" }
        val secretKey = key()
        val editor = prefs.edit()
        values.forEach { (name, value) ->
            require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
            require(value.length <= 100_000) { "Data too large for $name" }
            val plain = value.toByteArray(Charsets.UTF_8)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val encrypted = cipher.doFinal(plain)
                val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                editor.putString(name, payload)
                encrypted.fill(0)
            } finally {
                plain.fill(0)
            }
        }
        check(editor.commit()) { "Atomic encrypted save failed" }
    }

    @Synchronized fun remove(name: String): Boolean = prefs.edit().remove(name).commit()

    /** Atomically replace encrypted records and remove related keys in one transaction. */
    @Synchronized fun putAllAndRemove(values: Map<String, String>, namesToRemove: Set<String>): Result<Unit> = runCatching {
        val secretKey = key()
        val editor = prefs.edit()
        namesToRemove.forEach { name ->
            require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
            editor.remove(name)
        }
        values.forEach { (name, value) ->
            require(name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
            require(value.length <= 100_000) { "Data too large for $name" }
            val plain = value.toByteArray(Charsets.UTF_8)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val encrypted = cipher.doFinal(plain)
                editor.putString(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP))
                encrypted.fill(0)
            } finally {
                plain.fill(0)
            }
        }
        check(editor.commit()) { "Atomic encrypted update/delete failed" }
    }


    /** Read and decrypt every stored record whose key belongs to an explicitly selected family. */
    @Synchronized fun getAllWithPrefixes(prefixes: Set<String>): Result<Map<String, String>> = runCatching {
        require(prefixes.isNotEmpty())
        prefixes.forEach { require(it.matches(Regex("[a-zA-Z0-9_.-]{1,80}"))) }
        val keys = prefs.all.keys.filter { key -> prefixes.any { key.startsWith(it) } }.sorted()
        keys.associateWith { key -> get(key).getOrThrow() }
    }

    /** Replace complete selected record families in one encrypted preference transaction. */
    @Synchronized fun replaceAllWithPrefixes(values: Map<String, String>, prefixes: Set<String>): Result<Unit> = runCatching {
        require(prefixes.isNotEmpty())
        prefixes.forEach { require(it.matches(Regex("[a-zA-Z0-9_.-]{1,80}"))) }
        values.keys.forEach { key ->
            require(prefixes.any { key.startsWith(it) }) { "Backup contains an unapproved feature key: $key" }
        }
        val oldKeys = prefs.all.keys.filter { key -> prefixes.any { key.startsWith(it) } }.toSet()
        val removals = oldKeys - values.keys
        putAllAndRemove(values, removals).getOrThrow()
    }

    /** Atomically remove all encrypted entries in one namespaced feature family. */
    @Synchronized fun removeAllWithPrefix(prefix: String): Boolean {
        require(prefix.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        return editor.commit()
    }
}
