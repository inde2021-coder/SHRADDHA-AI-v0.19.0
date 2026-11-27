package `in`.shraddha.ai.operator.website

/** Allowlist for ordinary text inputs; selects and radio groups use separate exact-match policies. */
internal object AkkpsRunnerFieldPolicy {
    val allowedInputTypes = setOf("text", "number", "tel", "email", "date", "search", "url", "")
    val blockedInputTypes = setOf(
        "hidden", "password", "file", "checkbox", "radio",
        "button", "submit", "reset", "image", "color", "range"
    )

    fun isAllowedInputType(type: String): Boolean = type.trim().lowercase() in allowedInputTypes

    /** Only ordinary single-select controls may be filled; multi-select is always blocked. */
    fun isAllowedSelect(multiple: Boolean, disabled: Boolean): Boolean = !multiple && !disabled
}
