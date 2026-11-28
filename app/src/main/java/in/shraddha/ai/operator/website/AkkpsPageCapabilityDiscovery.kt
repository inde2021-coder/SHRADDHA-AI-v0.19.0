package `in`.shraddha.ai.operator.website

import java.util.Locale

/**
 * Derives only capabilities supported by visible, observed page evidence.
 * Absence on one page remains UNKNOWN; it is not proof that a role lacks access.
 */
object AkkpsPageCapabilityDiscovery {
    fun discover(
        formCount: Int,
        tableCount: Int,
        fieldTypes: List<String>,
        fieldLabels: List<String>,
        actionLabels: List<String>,
        navigationLabels: List<String>,
        isCreateRoute: Boolean = false,
        isDetailRoute: Boolean = false,
        supportedDataEntryModule: Boolean = false
    ): List<WebsiteCapability> {
        require(formCount >= 0 && tableCount >= 0)
        val all = (fieldLabels + actionLabels + navigationLabels).map {
            it.lowercase(Locale.ROOT).trim()
        }.filter { it.isNotBlank() }
        val actions = actionLabels.map { it.lowercase(Locale.ROOT).trim() }
        val types = fieldTypes.map { it.lowercase(Locale.ROOT).trim() }
        fun evidence(pattern: Regex): String? = all.firstOrNull { pattern.containsMatchIn(it) }
        fun actionEvidence(pattern: Regex): String? = actions.firstOrNull { pattern.containsMatchIn(it) }
        fun capability(operation: WebsiteOperation, found: String?, needsKnownDataEntryModule: Boolean = false): WebsiteCapability {
            if (!AkkpsWebsiteProfile.isRunnerOperationAllowed(operation)) {
                return WebsiteCapability(operation, CapabilityState.UNAVAILABLE, "Blocked by AKKPS Runner data-entry scope")
            }
            if (needsKnownDataEntryModule && !supportedDataEntryModule) {
                return WebsiteCapability(operation, CapabilityState.UNKNOWN, "Module observed; data-entry adapter has not been verified for this module")
            }
            return WebsiteCapability(
                operation,
                if (found == null) CapabilityState.UNKNOWN else CapabilityState.AVAILABLE,
                found.orEmpty().take(120)
            )
        }

        val search = evidence(Regex("search|find|filter|खोज|ढूंढ|ढूँढ"))
            ?: if (types.any { it == "search" }) "visible search field" else null
        val create = actionEvidence(Regex("create|add new|new record|नया|नई|जोड़|जोड़|बनाएं|बनाये"))
            ?: if (isCreateRoute) "observed create route" else null
        val update = actionEvidence(Regex("edit|update|modify|संपादित|बदलें|बदलना"))
        val save = actionEvidence(Regex("save|सहेज|सेव"))
        val submit = actionEvidence(Regex("submit|send|जमा|भेजें|सबमिट"))
        val upload = if (types.any { it == "file" } || actionEvidence(Regex("upload|attach|अपलोड|संलग्न")) != null)
            "visible file input/upload action" else null
        val download = actionEvidence(Regex("download|डाउनलोड"))
        val export = actionEvidence(Regex("export|report|निर्यात|रिपोर्ट"))
        val delete = actionEvidence(Regex("delete|remove|हटाएं|मिटाएं|डिलीट"))
        val payment = actionEvidence(Regex("payment|pay|भुगतान|पेमेंट"))
        val view = when {
            isDetailRoute -> "observed record detail route"
            tableCount > 0 -> "visible data table"
            else -> null
        }

        return listOf(
            WebsiteCapability(WebsiteOperation.DISCOVER_SITE, CapabilityState.AVAILABLE, "AKKPS page loaded"),
            WebsiteCapability(WebsiteOperation.OBSERVE_PAGE, CapabilityState.AVAILABLE, "Visible page structure inspected"),
            WebsiteCapability(WebsiteOperation.NAVIGATE,
                if (navigationLabels.isNotEmpty()) CapabilityState.AVAILABLE else CapabilityState.UNKNOWN,
                if (navigationLabels.isNotEmpty()) "Observed Runner navigation links" else ""),
            capability(WebsiteOperation.SEARCH_RECORDS, search, needsKnownDataEntryModule = true),
            capability(WebsiteOperation.VIEW_RECORD, view, needsKnownDataEntryModule = true),
            capability(WebsiteOperation.CREATE_RECORD, create, needsKnownDataEntryModule = true),
            capability(WebsiteOperation.UPDATE_RECORD, update, needsKnownDataEntryModule = true),
            capability(WebsiteOperation.SAVE_RECORD, save, needsKnownDataEntryModule = true),
            capability(WebsiteOperation.SUBMIT_FORM, submit, needsKnownDataEntryModule = true),
            capability(WebsiteOperation.UPLOAD_DOCUMENT, upload, needsKnownDataEntryModule = true),
            capability(WebsiteOperation.DOWNLOAD_DOCUMENT, download),
            capability(WebsiteOperation.EXPORT_REPORT, export),
            capability(WebsiteOperation.DELETE_RECORD, delete),
            capability(WebsiteOperation.PAYMENT, payment)
        )
    }
}
