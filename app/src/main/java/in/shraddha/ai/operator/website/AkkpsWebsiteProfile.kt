package `in`.shraddha.ai.operator.website

import java.net.URI
import java.util.Locale

/**
 * Security and capability contract for the dedicated AKKPS website connector.
 *
 * This profile deliberately does not assume that private dashboard modules exist.
 * They remain UNKNOWN until discovered in an authenticated, user-authorized session.
 */
enum class WebsiteOperation {
    DISCOVER_SITE,
    OBSERVE_PAGE,
    NAVIGATE,
    SEARCH_RECORDS,
    VIEW_RECORD,
    CREATE_RECORD,
    UPDATE_RECORD,
    SAVE_RECORD,
    SUBMIT_FORM,
    UPLOAD_DOCUMENT,
    DOWNLOAD_DOCUMENT,
    EXPORT_REPORT,
    DELETE_RECORD,
    PAYMENT
}

enum class CapabilityState {
    AVAILABLE,
    UNAVAILABLE,
    NEEDS_AUTHENTICATION,
    NEEDS_USER_PERMISSION,
    UNKNOWN
}

data class WebsiteCapability(
    val operation: WebsiteOperation,
    val state: CapabilityState = CapabilityState.UNKNOWN,
    val evidence: String = ""
)

object AkkpsWebsiteProfile {
    const val CANONICAL_URL = "https://www.akkps.ngo"
    private val allowedHosts = setOf("akkps.ngo", "www.akkps.ngo")

    /**
     * Only HTTPS URLs on the exact AKKPS host are accepted. This prevents a
     * malicious command or redirect from reusing the site's authenticated session
     * on an unrelated origin.
     */
    fun acceptsUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank() || rawUrl.length > 2048) return false
        return runCatching {
            val uri = URI(rawUrl.trim())
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            uri.scheme.equals("https", ignoreCase = true) &&
                host in allowedHosts &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)
        }.getOrDefault(false)
    }

    /** All private-site capabilities start unknown until observed and tested. */
    fun initialCapabilities(): List<WebsiteCapability> =
        WebsiteOperation.entries.map { operation ->
            WebsiteCapability(
                operation = operation,
                state = if (operation == WebsiteOperation.DISCOVER_SITE ||
                    operation == WebsiteOperation.OBSERVE_PAGE ||
                    operation == WebsiteOperation.NAVIGATE
                ) CapabilityState.AVAILABLE else CapabilityState.UNKNOWN,
                evidence = if (operation == WebsiteOperation.DISCOVER_SITE)
                    "Public site origin configured; private dashboard not yet inspected."
                else ""
            )
        }

    /**
     * Runner is intentionally limited to record lookup and Farmer/Plot/AWD data entry.
     * Reporting exports, downloads, destructive actions and payments are out of scope.
     */
    fun isRunnerOperationAllowed(operation: WebsiteOperation): Boolean =
        operation in setOf(
            WebsiteOperation.DISCOVER_SITE,
            WebsiteOperation.OBSERVE_PAGE,
            WebsiteOperation.NAVIGATE,
            WebsiteOperation.SEARCH_RECORDS,
            WebsiteOperation.VIEW_RECORD,
            WebsiteOperation.CREATE_RECORD,
            WebsiteOperation.UPDATE_RECORD,
            WebsiteOperation.SAVE_RECORD,
            WebsiteOperation.SUBMIT_FORM,
            WebsiteOperation.UPLOAD_DOCUMENT
        )

    /**
     * High-impact operations always require a separate safety decision.
     * Ordinary record viewing/searching/navigation does not.
     */
    fun requiresHighImpactConfirmation(operation: WebsiteOperation): Boolean =
        operation in setOf(
            WebsiteOperation.DELETE_RECORD,
            WebsiteOperation.PAYMENT
        )

    /** Never treat a click or a submit request as proof that a record was saved. */
    fun canReportSuccess(
        operation: WebsiteOperation,
        actionDispatched: Boolean,
        postconditionVerified: Boolean
    ): Boolean = actionDispatched && postconditionVerified &&
        operation !in setOf(WebsiteOperation.DISCOVER_SITE, WebsiteOperation.NAVIGATE)
}
