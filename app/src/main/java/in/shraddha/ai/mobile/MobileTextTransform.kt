package `in`.shraddha.ai.mobile

import java.util.Locale

/** Deterministic, offline text transforms for the user-invoked mobile clipboard workspace. */
object MobileTextTransform {
    enum class Operation { TRIM, UPPERCASE, LOWERCASE, COLLAPSE_WHITESPACE, REMOVE_BLANK_LINES }

    fun apply(source: String, operation: Operation): String = when (operation) {
        Operation.TRIM -> source.trim()
        Operation.UPPERCASE -> source.uppercase(Locale.ROOT)
        Operation.LOWERCASE -> source.lowercase(Locale.ROOT)
        Operation.COLLAPSE_WHITESPACE -> source.replace(Regex("\\s+"), " ").trim()
        Operation.REMOVE_BLANK_LINES -> source.lineSequence().filter { it.isNotBlank() }.joinToString("\n")
    }
}
