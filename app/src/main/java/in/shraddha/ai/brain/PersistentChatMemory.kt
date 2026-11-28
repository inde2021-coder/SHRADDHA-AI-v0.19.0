package `in`.shraddha.ai.brain

import android.content.Context

/**
 * Opt-in, device-local conversation recap. Content is encrypted by SecureMemoryVault
 * using an Android Keystore AES-GCM key. No cloud sync or external sharing.
 */
object PersistentChatMemory {
    private const val PREFS = "nova_prefs"
    private const val ENABLED_KEY = "remember_conversations"
    private const val MAX_TURNS = 12
    private const val MAX_TURN_CHARS = 1_000

    private val lock = Any()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        synchronized(lock) {
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED_KEY, enabled).commit()) {
                "Could not save conversation-memory consent."
            }
            // Revoking consent also removes the retained recap; hiding it is not deletion.
            if (!enabled) {
                check(SecureMemoryVault(context.applicationContext).clear()) {
                    "Conversation memory could not be cleared after consent was revoked."
                }
            }
        }
    }

    fun recap(context: Context): String? {
        if (!isEnabled(context)) return null
        return SecureMemoryVault(context.applicationContext).load()
            .getOrThrow().takeIf { it.isNotBlank() }
    }

    fun record(context: Context, role: String, text: String): Result<Unit> = runCatching {
        require(role == "User" || role == "Assistant") { "Unsupported memory role" }
        val safeText = text.trim().take(MAX_TURN_CHARS)
        if (safeText.isEmpty() || !isEnabled(context)) return@runCatching
        synchronized(lock) {
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
