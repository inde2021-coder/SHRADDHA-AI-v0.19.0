package `in`.shraddha.ai.operator

/**
 * Short-lived, process-local reference context for follow-up commands ("उसे भेजो", "same file").
 * Only explicitly selected targets may be remembered. No contact, file, record or message data
 * is persisted to disk or cloud by this class.
 */
enum class CommandReferenceType { CONTACT, APP, FILE, WEBSITE, AKKPS_RECORD, DEVICE }

data class ExplicitCommandReference(
    val type: CommandReferenceType,
    val stableId: String,
    val displayLabel: String,
    val selectedAtMillis: Long
)

sealed class CommandReferenceResolution {
    data class Resolved(val reference: ExplicitCommandReference) : CommandReferenceResolution()
    data object NoReferenceRequested : CommandReferenceResolution()
    data object MissingOrExpired : CommandReferenceResolution()
    data object TypeMismatch : CommandReferenceResolution()
}

/**
 * Stores at most one explicit reference per type for a short session window.
 * The caller must still revalidate that the referenced item exists and is authorized.
 */
class CommandFollowUpContext(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS
) {
    private val references = LinkedHashMap<CommandReferenceType, ExplicitCommandReference>()

    init { require(ttlMillis in 1_000L..30 * 60 * 1000L) }

    @Synchronized
    fun rememberExplicitSelection(type: CommandReferenceType, stableId: String, displayLabel: String): Boolean {
        val id = stableId.trim()
        val label = displayLabel.trim()
        if (id.isBlank() || id.length > 256 || label.isBlank() || label.length > 120) return false
        val selectedAt = nowMillis()
        // Require a positive wall-clock timestamp so age arithmetic cannot overflow
        // on malformed/future clock values and references fail closed after clock rollback.
        if (selectedAt <= 0L) return false
        references[type] = ExplicitCommandReference(type, id, label, selectedAt)
        while (references.size > MAX_REFERENCES) references.remove(references.keys.first())
        return true
    }

    @Synchronized
    fun resolve(command: String, expectedType: CommandReferenceType): CommandReferenceResolution {
        if (!requestsPriorReference(command)) return CommandReferenceResolution.NoReferenceRequested
        val reference = references[expectedType] ?: return CommandReferenceResolution.MissingOrExpired
        val now = nowMillis()
        if (now <= 0L || reference.selectedAtMillis <= 0L || now < reference.selectedAtMillis) {
            references.remove(expectedType)
            return CommandReferenceResolution.MissingOrExpired
        }
        val age = now - reference.selectedAtMillis
        if (age > ttlMillis) {
            references.remove(expectedType)
            return CommandReferenceResolution.MissingOrExpired
        }
        if (reference.type != expectedType) return CommandReferenceResolution.TypeMismatch
        return CommandReferenceResolution.Resolved(reference)
    }

    @Synchronized
    fun clear(type: CommandReferenceType? = null) {
        if (type == null) references.clear() else references.remove(type)
    }

    @Synchronized
    fun clearExpired() {
        val now = nowMillis()
        references.entries.removeAll { (_, ref) ->
            now <= 0L || ref.selectedAtMillis <= 0L ||
                now < ref.selectedAtMillis || now - ref.selectedAtMillis > ttlMillis
        }
    }

    private fun requestsPriorReference(command: String): Boolean {
        val text = command.trim().lowercase().replace(Regex("\\s+"), " ")
        if (text.isBlank() || text.length > 2_000) return false
        return FOLLOW_UP_PATTERNS.any { it.containsMatchIn(text) }
    }

    companion object {
        private const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
        private const val MAX_REFERENCES = 6
        private val FOLLOW_UP_PATTERNS = listOf(
            Regex("""(?<![\p{L}\p{N}])(?:it|that one|same one|same file|same app|same person)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE),
            Regex("""(?<![\p{L}\p{N}])(?:उसे|उसको|उसी को|वही|उस वाली|उस वाले|पहले वाला|पहले वाली|उसी फाइल|उसी ऐप)(?![\p{L}\p{N}])""")
        )
    }
}
