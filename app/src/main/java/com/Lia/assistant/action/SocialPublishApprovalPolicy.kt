package com.Lia.assistant.action

/** Exact-preview guard for the text-only social publishing endpoints. */
object SocialPublishApprovalPolicy {
    private val platforms = setOf("telegram", "mastodon", "bluesky", "linkedin")

    fun maxCharacters(platform: String): Int = when (platform.trim().lowercase()) {
        "bluesky" -> 300
        "linkedin" -> 3000
        "telegram", "mastodon" -> 5000
        else -> 0
    }

    fun validate(platform: String, action: String, text: String): String? {
        val normalizedPlatform = platform.trim().lowercase()
        if (normalizedPlatform !in platforms) return "Unsupported social platform"
        if (action.trim().uppercase() !in setOf("TEXT_POST", "CREATE_POST")) {
            return "Only text posts are supported; media, Reels and Stories are unavailable"
        }
        if (text.isBlank()) return "Post text is required"
        // Bluesky counts Unicode code points; the other backend adapters enforce JS string length.
        val length = if (normalizedPlatform == "bluesky") text.codePointCount(0, text.length) else text.length
        if (length > maxCharacters(normalizedPlatform)) return "Post exceeds the selected platform character limit"
        return null
    }

    fun isApproved(
        platform: String,
        exactText: String,
        userConfirmed: Boolean,
        confirmedPlatform: String,
        confirmedText: String
    ): Boolean = userConfirmed &&
        confirmedPlatform.trim().equals(platform.trim(), ignoreCase = true) &&
        confirmedText == exactText
}
