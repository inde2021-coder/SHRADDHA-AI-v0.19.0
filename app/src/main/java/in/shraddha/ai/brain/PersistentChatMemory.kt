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

    @Volatile private var lock = Any()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun recap(context: Context): String? {
        if (!isEnabled(context)) return null
        return SecureMemoryVault(context.applicationContext).load()
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun record(context: Context, role: String, text: String): Result<Unit> = runCatching {
        require(role == "User" || role == "Assistant") { "Unsupported memory role" }
        val safeText = text.trim().take(MAX_TURN_CHARS)
        if (safeText.isEmpty() || !isEnabled(context)) return@runCatching
        synchronized(lock) {
            val vault = SecureMemoryVault(context.applicationContext)
            val existing = vault.load().getOrElse { "" }
            val turns = existing.lineSequence().filter { it.isNotBlank() }.toMutableList()
            turns.add("$role: $safeText")
            val bounded = turns.takeLast(MAX_TURNS).joinToString("\n").takeLast(12_000)
            vault.save(bounded).getOrThrow()
        }
    }

    fun clear(context: Context): Boolean =
        SecureMemoryVault(context.applicationContext).clear()
}
