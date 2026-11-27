package `in`.shraddha.ai.operator.website

/** AKKPS Runner plot identifiers are treated as opaque, bounded path/storage keys. */
internal object AkkpsPlotIdPolicy {
    fun isValid(raw: String): Boolean = raw.trim().matches(Regex("[A-Za-z0-9_-]{1,80}"))
}
