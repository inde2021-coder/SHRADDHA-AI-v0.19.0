package `in`.shraddha.ai.learning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Local-only learning progress. Progress is AES-GCM encrypted with an Android Keystore key. */
class CurriculumProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val completedVerses: Set<String>,
        val completedPacks: Set<String>,
        val guardianMode: Boolean,
        val dailyLimitMinutes: Int,
        val usedTodayMinutes: Int
    ) {
        val hasPin: Boolean get() = false
    }

    @Synchronized fun hasGuardianPin(): Boolean = prefs.contains(PIN_HASH)

    @Synchronized fun setGuardianPin(pin: CharArray) {
        require(pin.size in 4..12 && pin.all { it.isDigit() }) { "Parent PIN must be 4–12 digits." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derivePinHash(pin, salt)
        prefs.edit()
            .putString(PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        pin.fill('\u0000')
    }

    @Synchronized fun verifyGuardianPin(pin: CharArray): Boolean {
        val salt = prefs.getString(PIN_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString(PIN_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val actual = derivePinHash(pin, salt)
        pin.fill('\u0000')
        return MessageDigest.isEqual(expected, actual)
    }

    @Synchronized fun snapshot(): Snapshot {
        val state = readState()
        val today = java.time.LocalDate.now().toString()
        if (state.optString("usageDate") != today) {
            state.put("usageDate", today).put("usedTodayMinutes", 0)
            writeState(state)
        }
        return Snapshot(
            completedVerses = state.stringSet("completedVerses"),
            completedPacks = state.stringSet("completedPacks"),
            guardianMode = state.optBoolean("guardianMode", false),
            dailyLimitMinutes = state.optInt("dailyLimitMinutes", 45).coerceIn(5, 240),
            usedTodayMinutes = state.optInt("usedTodayMinutes", 0).coerceAtLeast(0)
        )
    }

    @Synchronized fun setGuardianMode(enabled: Boolean) {
        require(!enabled || hasGuardianPin()) { "Set a parent PIN before enabling Guardian Mode." }
        writeState(readState().put("guardianMode", enabled))
    }

    @Synchronized fun setDailyLimit(minutes: Int) {
        require(minutes in 5..240) { "Daily learning limit must be 5–240 minutes." }
        writeState(readState().put("dailyLimitMinutes", minutes))
    }

    @Synchronized fun markVerseComplete(reference: String) {
        require(reference.matches(Regex("(?:[1-9]|1[0-8])\\.(?:[1-9]|[1-7][0-9])")))
        val state = readState()
        val values = state.stringSet("completedVerses").toMutableSet()
        values.add(reference)
        state.put("completedVerses", JSONArray(values.sorted()))
        writeState(state)
    }

    @Synchronized fun markPackComplete(packId: String) {
        require(packId.matches(Regex("curriculum_[a-z0-9_]{3,60}")))
        val state = readState()
        val values = state.stringSet("completedPacks").toMutableSet()
        values.add(packId)
        state.put("completedPacks", JSONArray(values.sorted()))
        writeState(state)
    }

    @Synchronized fun canStartLearning(): Boolean {
        val current = snapshot()
        return !current.guardianMode || current.usedTodayMinutes < current.dailyLimitMinutes
    }

    @Synchronized fun startSession() {
        prefs.edit().putLong(SESSION_STARTED_AT, System.currentTimeMillis()).apply()
    }

    @Synchronized fun endSession() {
        val started = prefs.getLong(SESSION_STARTED_AT, 0L)
        prefs.edit().remove(SESSION_STARTED_AT).apply()
        if (started <= 0L) return
        val elapsed = ((System.currentTimeMillis() - started).coerceAtLeast(0L) / 60_000.0).let { kotlin.math.ceil(it).toInt() }
        if (elapsed <= 0) return
        val state = readState()
        val today = java.time.LocalDate.now().toString()
        val used = if (state.optString("usageDate") == today) state.optInt("usedTodayMinutes", 0) else 0
        state.put("usageDate", today).put("usedTodayMinutes", (used + elapsed).coerceAtMost(24 * 60))
        writeState(state)
    }

    private fun JSONObject.stringSet(key: String): Set<String> {
        val array = optJSONArray(key) ?: JSONArray()
        return buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun readState(): JSONObject {
        val encoded = prefs.getString(ENCRYPTED_STATE, null) ?: return JSONObject()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES) { "Learning progress data is incomplete." }
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
    }

    private fun writeState(state: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        check(prefs.edit().putString(ENCRYPTED_STATE, Base64.encodeToString(combined, Base64.NO_WRAP)).commit()) {
            "Could not save encrypted learning progress."
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun derivePinHash(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, PIN_ITERATIONS, PIN_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val PREFS = "shraddha_curriculum_progress_v1"
        private const val ENCRYPTED_STATE = "encrypted_state"
        private const val SESSION_STARTED_AT = "session_started_at"
        private const val PIN_SALT = "guardian_pin_salt"
        private const val PIN_HASH = "guardian_pin_hash"
        private const val KEY_ALIAS = "shraddha_curriculum_progress_aes_v1"
        private const val GCM_IV_BYTES = 12
        private const val PIN_ITERATIONS = 160_000
        private const val PIN_KEY_BITS = 256
    }
}
