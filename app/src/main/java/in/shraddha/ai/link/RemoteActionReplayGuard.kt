package `in`.shraddha.ai.link

import android.os.SystemClock

/**
 * Bounded, process-local replay guard for end-to-end remote actions.
 * Action IDs are scoped to the authenticated peer and expire after the session grace window.
 */
class RemoteActionReplayGuard(
    maxEntries: Int = 4096,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val capacity = maxEntries.coerceIn(1, 8192)
    private val lock = Any()
    private val seen = LinkedHashMap<String, Long>()

    fun claim(peerId: String, actionId: String): Boolean {
        if (peerId.isBlank() || peerId.length > 80 ||
            !actionId.matches(Regex("[A-Za-z0-9_-]{12,64}")) || ttlMs <= 0L
        ) return false

        synchronized(lock) {
            val now = nowMs()
            val iterator = seen.entries.iterator()
            while (iterator.hasNext()) {
                val age = now - iterator.next().value
                if (age >= ttlMs || age < 0L) iterator.remove()
            }

            val key = "$peerId|$actionId"
            if (seen.containsKey(key)) return false
            while (seen.size >= capacity) {
                val oldest = seen.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
            seen[key] = now
            return true
        }
    }

    fun size(): Int = synchronized(lock) { seen.size }
}
