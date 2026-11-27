package `in`.shraddha.ai.operator.website

/** Only ordinary, single-value text entry controls are eligible for automatic filling. */
internal object AkkpsRunnerFieldPolicy {
    val allowedInputTypes = setOf("text", "number", "tel", "email", "date", "search", "url", "")
    val blockedInputTypes = setOf(
        "hidden", "password", "file", "checkbox", "radio",
        "button", "submit", "reset", "image", "color", "range"
    )

    fun isAllowedInputType(type: String): Boolean = type.trim().lowercase() in allowedInputTypes
}
