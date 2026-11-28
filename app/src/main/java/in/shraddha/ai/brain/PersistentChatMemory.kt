package `in`.shraddha.ai.brain

import android.content.Context

/**
 * Opt-in, device-local conversation recap. Content is encrypted by SecureMemoryVault
 * using an Android Keystore AES-GCM key. No cloud sync or external sharing.
 */
object PersistentChatMemory {
    private const val PREFS = "nova_prefs"
    private const val ENABLED_KEY = "remember_conversations"
    private const val CLEAR_PENDING_KEY = "conversation_memory_clear_pending"
    private const val MAX_TURNS = 12
    private const val MAX_TURN_CHARS = 1_000

    private val lock = Any()

    fun isEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(ENABLED_KEY, false) && !prefs.getBoolean(CLEAR_PENDING_KEY, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        synchronized(lock) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val vault = SecureMemoryVault(appContext)
            if (!enabled) {
                // Persist a revocation tombstone before deleting the vault. If deletion fails,
                // stale recap data must remain inaccessible and cannot reappear on re-enable.
                check(prefs.edit()
                    .putBoolean(ENABLED_KEY, false)
                    .putBoolean(CLEAR_PENDING_KEY, true)
                    .commit()) { "Could not save conversation-memory revocation." }
                check(vault.clear()) {
                    "Conversation memory deletion is pending; memory remains disabled until it succeeds."
                }
                check(prefs.edit().remove(CLEAR_PENDING_KEY).commit()) {
                    "Conversation memory was cleared, but its revocation marker could not be finalized."
                }
            } else {
                // Complete any interrupted revocation before consent can be enabled again.
                if (prefs.getBoolean(CLEAR_PENDING_KEY, false)) {
                    check(vault.clear()) {
                        "Previous conversation-memory deletion is still pending; memory cannot be re-enabled."
                    }
                    check(prefs.edit().remove(CLEAR_PENDING_KEY).commit()) {
                        "Could not finalize previous conversation-memory deletion."
                    }
                }
                check(prefs.edit().putBoolean(ENABLED_KEY, true).commit()) {
                    "Could not save conversation-memory consent."
                }
            }
        }
    }

    fun recap(context: Context): String? = synchronized(lock) {
        if (!isEnabled(context)) return@synchronized null
        SecureMemoryVault(context.applicationContext).load()
            .getOrThrow().takeIf { it.isNotBlank() }
    }

    fun record(context: Context, role: String, text: String): Result<Unit> = runCatching {
        require(role == "User" || role == "Assistant") { "Unsupported memory role" }
        val safeText = text.trim().take(MAX_TURN_CHARS)
        if (safeText.isEmpty()) return@runCatching
        synchronized(lock) {
            // Re-check consent inside the same lock as revocation/clear to prevent a late write.
            if (!isEnabled(context)) return@synchronized
            val vault = SecureMemoryVault(context.applicationContext)
            // Never overwrite unreadable/corrupt encrypted data with a fresh recap.
            val existing = vault.load().getOrThrow()
            vault.save(mergeRecap(existing, role, safeText)).getOrThrow()
        }
    }

    internal fun mergeRecap(existing: String, role: String, text: String): String {
        val turns = existing.lineSequence().filter { it.isNotBlank() }.toMutableList()
        turns.add("$role: ${text.trim().take(MAX_TURN_CHARS)}")
        return turns.takeLast(MAX_TURNS).joinToString("\n").takeLast(12_000)
    }

    fun restore(context: Context, recap: String): Result<Unit> = runCatching {
        synchronized(lock) {
            require(isEnabled(context)) { "Turn on Encrypted Conversation Memory before restoring a recap." }
            val safe = recap.trim()
            require(safe.isNotEmpty() && safe.length <= 12_000) { "Restored recap must contain 1–12,000 characters." }
            SecureMemoryVault(context.applicationContext).save(safe).getOrThrow()
        }
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        SecureMemoryVault(context.applicationContext).clear()
    }
}
