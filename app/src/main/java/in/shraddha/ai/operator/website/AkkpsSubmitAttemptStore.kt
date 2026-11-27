package `in`.shraddha.ai.operator.website

import org.json.JSONObject

enum class AkkpsSubmitAttemptState { PENDING, REVIEW_REQUIRED }

data class AkkpsSubmitAttempt(
    val state: AkkpsSubmitAttemptState,
    val createdAtEpochMillis: Long
)

object AkkpsSubmitAttemptCodec {
    fun encode(attempt: AkkpsSubmitAttempt): String {
        require(attempt.createdAtEpochMillis > 0L &&
            attempt.createdAtEpochMillis <= System.currentTimeMillis() + 60_000L)
        return JSONObject()
            .put("version", 1)
            .put("state", attempt.state.name)
            .put("createdAt", attempt.createdAtEpochMillis)
            .toString()
    }

    fun decode(raw: String): AkkpsSubmitAttempt {
        require(raw.length in 1..1_000) { "AKKPS submit attempt record size is invalid." }
        val json = JSONObject(raw)
        require(json.optInt("version") == 1) { "AKKPS submit attempt version is unsupported." }
        val state = AkkpsSubmitAttemptState.valueOf(json.getString("state"))
        val createdAt = json.getLong("createdAt")
        require(createdAt > 0L && createdAt <= System.currentTimeMillis() + 60_000L) {
            "AKKPS submit attempt timestamp is invalid."
        }
        return AkkpsSubmitAttempt(state, createdAt)
    }
}

/**
 * Encrypted duplicate-submit guard. Stores only state and timestamp; never stores farmer data,
 * field names, field values, session tokens or API responses.
 */
class AkkpsSubmitAttemptStore(context: android.content.Context) {
    private val encrypted = `in`.shraddha.ai.brain.EncryptedFeatureStore(context.applicationContext)
    private val key = "akkps_submit_attempt_guard_v1"

    fun load(): AkkpsSubmitAttempt? {
        val raw = encrypted.get(key).getOrThrow()
        return if (raw.isBlank()) null else AkkpsSubmitAttemptCodec.decode(raw)
    }

    fun markPending() {
        encrypted.put(
            key,
            AkkpsSubmitAttemptCodec.encode(
                AkkpsSubmitAttempt(AkkpsSubmitAttemptState.PENDING, System.currentTimeMillis())
            )
        ).getOrThrow()
    }

    fun markReviewRequired() {
        encrypted.put(
            key,
            AkkpsSubmitAttemptCodec.encode(
                AkkpsSubmitAttempt(AkkpsSubmitAttemptState.REVIEW_REQUIRED, System.currentTimeMillis())
            )
        ).getOrThrow()
    }

    fun clear() {
        check(encrypted.remove(key)) { "AKKPS submit attempt guard could not be cleared." }
    }
}
