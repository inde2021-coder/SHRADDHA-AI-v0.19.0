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
import java.time.LocalDate
import java.time.ZoneId
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
        val usedTodayMinutes: Int,
        val guardianOverrideUntilMillis: Long
    ) {
    }

    @Synchronized fun hasGuardianPin(): Boolean = prefs.contains(PIN_HASH)

    @Synchronized fun setGuardianPin(pin: CharArray) {
        try {
            require(pin.size in 4..12 && pin.all { it.isDigit() }) { "Parent PIN must be 4–12 digits." }
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val hash = derivePinHash(pin, salt)
            try {
                check(prefs.edit()
                    .putString(PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .remove(PIN_FAILED_ATTEMPTS)
                    .remove(PIN_LOCK_UNTIL)
                    .commit()) { "Could not save the guardian PIN." }
            } finally {
                salt.fill(0)
                hash.fill(0)
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    @Synchronized fun verifyGuardianPin(pin: CharArray): Boolean =
        verifyGuardianPinAt(pin, System.currentTimeMillis())

    /** Clock-injectable PIN verification for deterministic lockout tests. */
    @Synchronized internal fun verifyGuardianPinAt(pin: CharArray, nowMillis: Long): Boolean {
        var salt: ByteArray? = null
        var expected: ByteArray? = null
        var actual: ByteArray? = null
        try {
            require(nowMillis > 0L)
            val lockedUntil = prefs.getLong(PIN_LOCK_UNTIL, 0L)
            if (nowMillis < lockedUntil) return false
            if (lockedUntil > 0L) {
                check(prefs.edit().remove(PIN_LOCK_UNTIL).putInt(PIN_FAILED_ATTEMPTS, 0).commit()) {
                    "Could not reset guardian PIN lockout state."
                }
            }
            salt = prefs.getString(PIN_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
            expected = prefs.getString(PIN_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
            actual = derivePinHash(pin, salt)
            val matches = MessageDigest.isEqual(expected, actual)
            if (matches) {
                check(prefs.edit().remove(PIN_FAILED_ATTEMPTS).remove(PIN_LOCK_UNTIL).commit()) {
                    "Could not clear guardian PIN failure state."
                }
            } else {
                val attempts = prefs.getInt(PIN_FAILED_ATTEMPTS, 0) + 1
                val editor = prefs.edit().putInt(PIN_FAILED_ATTEMPTS, attempts)
                if (attempts >= MAX_PIN_ATTEMPTS) {
                    editor.putLong(PIN_LOCK_UNTIL, nowMillis + PIN_LOCKOUT_MILLIS)
                }
                check(editor.commit()) { "Could not save guardian PIN failure state." }
            }
            return matches
        } finally {
            pin.fill('\u0000')
            salt?.fill(0)
            expected?.fill(0)
            actual?.fill(0)
        }
    }

    @Synchronized fun snapshot(): Snapshot {
        val state = readState()
        val today = java.time.LocalDate.now().toString()
        if (state.optString("usageDate") != today) {
            state.put("usageDate", today).put("usedTodayMinutes", 0)
                .remove("guardianOverrideUntilMillis")
            writeState(state)
        }
        return Snapshot(
            completedVerses = state.stringSet("completedVerses"),
            completedPacks = state.stringSet("completedPacks"),
            guardianMode = state.optBoolean("guardianMode", false),
            dailyLimitMinutes = state.optInt("dailyLimitMinutes", 45).coerceIn(5, 240),
            usedTodayMinutes = (state.optInt("usedTodayMinutes", 0).coerceAtLeast(0) + activeSessionMinutesToday(state)).coerceAtMost(24 * 60),
            guardianOverrideUntilMillis = state.optLong("guardianOverrideUntilMillis", 0L)
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

    /** Grants a short, encrypted, one-time learning extension after a successful parent PIN check. */
    @Synchronized fun grantGuardianOverride(minutes: Int = 15) {
        grantGuardianOverrideAt(System.currentTimeMillis(), minutes)
    }

    @Synchronized internal fun grantGuardianOverrideAt(nowMillis: Long, minutes: Int = 15) {
        require(nowMillis > 0L && minutes in 5..60)
        val state = readState()
        require(state.optBoolean("guardianMode", false) && hasGuardianPin()) {
            "Guardian Mode and a verified parent PIN are required for an extension."
        }
        state.put("guardianOverrideUntilMillis", nowMillis + minutes * 60_000L)
        writeState(state)
    }

    @Synchronized internal fun hasGuardianOverrideAt(nowMillis: Long): Boolean {
        require(nowMillis > 0L)
        return readState().optLong("guardianOverrideUntilMillis", 0L) > nowMillis
    }

    @Synchronized fun markVerseComplete(reference: String) {
        val parts = reference.split('.')
        val chapter = parts.getOrNull(0)?.toIntOrNull()
        val verse = parts.getOrNull(1)?.toIntOrNull()
        require(parts.size == 2 && chapter != null && verse != null && chapter in 1..18 &&
            verse in 1..GITA_CHAPTER_VERSE_COUNTS[chapter - 1]) {
            "Verse reference is outside the canonical 700-verse chapter sequence."
        }
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
        return !current.guardianMode || current.usedTodayMinutes < current.dailyLimitMinutes ||
            current.guardianOverrideUntilMillis > System.currentTimeMillis()
    }

    @Synchronized fun startSession() {
        // Recover a session left open by process death before starting a new one.
        if (prefs.getLong(SESSION_STARTED_AT, 0L) > 0L) endSession()
        val lastClosed = readState().optLong(LAST_CLOSED_SESSION_STARTED_AT, 0L)
        val startedAt = maxOf(System.currentTimeMillis(), lastClosed + 1L)
        check(prefs.edit().putLong(SESSION_STARTED_AT, startedAt).commit()) {
            "Could not persist the learning session marker."
        }
    }

    private fun activeSessionMinutesToday(state: JSONObject): Int {
        val started = prefs.getLong(SESSION_STARTED_AT, 0L)
        if (started <= 0L || state.optLong(LAST_CLOSED_SESSION_STARTED_AT, 0L) == started) return 0
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val countedFrom = maxOf(started, todayStart)
        if (countedFrom >= now) return 0
        val elapsed = (now - countedFrom).coerceAtMost(12L * 60L * 60L * 1000L)
        return kotlin.math.ceil(elapsed / 60_000.0).toInt()
    }

    @Synchronized fun endSession() {
        endSessionAt(System.currentTimeMillis(), clearSessionMarker = true)
    }

    /**
     * Persists the closed-session marker inside encrypted state before clearing the recovery marker.
     * If the process dies between those writes, the next recovery sees the same start timestamp and
     * clears the marker without adding the elapsed time twice.
     */
    @Synchronized internal fun endSessionAt(nowMillis: Long, clearSessionMarker: Boolean = true) {
        require(nowMillis > 0L)
        val started = prefs.getLong(SESSION_STARTED_AT, 0L)
        if (started <= 0L) return
        val state = readState()
        if (state.optLong(LAST_CLOSED_SESSION_STARTED_AT, 0L) == started) {
            if (clearSessionMarker) {
                check(prefs.edit().remove(SESSION_STARTED_AT).commit()) {
                    "Could not clear the recovered learning session marker."
                }
            }
            return
        }

        val zone = ZoneId.systemDefault()
        val todayDate = LocalDate.now(zone)
        val todayStart = todayDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val countedFrom = maxOf(started, todayStart)
        val elapsed = if (countedFrom >= nowMillis) 0 else {
            val bounded = (nowMillis - countedFrom).coerceAtMost(12L * 60L * 60L * 1000L)
            kotlin.math.ceil(bounded / 60_000.0).toInt()
        }
        val today = todayDate.toString()
        val used = if (state.optString("usageDate") == today) state.optInt("usedTodayMinutes", 0) else 0
        state.put(LAST_CLOSED_SESSION_STARTED_AT, started)
        if (elapsed > 0) {
            state.put("usageDate", today).put("usedTodayMinutes", (used + elapsed).coerceAtMost(24 * 60))
        }
        writeState(state)
        if (clearSessionMarker) {
            check(prefs.edit().remove(SESSION_STARTED_AT).commit()) {
                "Could not clear the completed learning session marker."
            }
        }
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
        private const val LAST_CLOSED_SESSION_STARTED_AT = "last_closed_session_started_at"
        private val GITA_CHAPTER_VERSE_COUNTS = intArrayOf(47, 72, 43, 42, 29, 47, 30, 28, 34, 42, 55, 20, 34, 27, 20, 24, 28, 78)
        private const val PIN_SALT = "guardian_pin_salt"
        private const val PIN_HASH = "guardian_pin_hash"
        private const val KEY_ALIAS = "shraddha_curriculum_progress_aes_v1"
        private const val GCM_IV_BYTES = 12
        private const val PIN_ITERATIONS = 160_000
        private const val PIN_KEY_BITS = 256
        private const val PIN_FAILED_ATTEMPTS = "guardian_pin_failed_attempts"
        private const val PIN_LOCK_UNTIL = "guardian_pin_lock_until"
        private const val MAX_PIN_ATTEMPTS = 5
        private const val PIN_LOCKOUT_MILLIS = 60_000L
    }
}
