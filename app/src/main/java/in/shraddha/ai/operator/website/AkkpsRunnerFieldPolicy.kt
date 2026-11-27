package `in`.shraddha.ai.operator.website

/** Input types that must never be auto-filled by the AKKPS text mapper. */
internal object AkkpsRunnerFieldPolicy {
    val blockedInputTypes = setOf(
        "hidden", "password", "file", "checkbox", "radio",
        "button", "submit", "reset", "image"
    )
}
