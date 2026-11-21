package `in`.shraddha.ai.operator

data class ScreenTextCandidate(
    val text: String,
    val description: String,
    val password: Boolean
)

/**
 * Verifies an action against a fresh accessibility snapshot.
 * Password fields are deliberately excluded from visible-result matching.
 */
object ScreenResultVerifier {
    fun containsExpectedText(snapshot: ScreenSnapshot, expectedText: String): Boolean =
        containsExpectedText(snapshot.elements.map { ScreenTextCandidate(it.text, it.description, it.password) }, expectedText)

    fun containsExpectedText(elements: Iterable<ScreenTextCandidate>, expectedText: String): Boolean {
        val expected = expectedText.trim()
        if (expected.isEmpty()) return true
        if (expected.length > 120) return false
        return elements.any { element ->
            !element.password && (
                element.text.contains(expected, ignoreCase = true) ||
                    element.description.contains(expected, ignoreCase = true)
                )
        }
    }
}
