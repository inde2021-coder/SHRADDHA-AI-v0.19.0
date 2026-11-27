package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Confirms that an Edit click exposed a real editable form and a visible save/submit control. */
internal object AkkpsEditFormVerificationPolicy {
    fun isVerified(
        expectedModule: AkkpsRunnerModule,
        route: AkkpsRunnerRouteMatch?,
        formCount: Int,
        fieldCount: Int,
        actionLabels: List<String>
    ): Boolean {
        if (route == null || route.module != expectedModule || !route.isDetailRoute) return false
        if (formCount !in 1..20 || fieldCount !in 1..100) return false
        return actionLabels.any { label ->
            val normalized = Normalizer.normalize(label, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
            Regex("(?:^|\\s)(?:save|submit|update|सहेजें|सेव|जमा|सबमिट|अपडेट)(?:$|\\s)")
                .containsMatchIn(normalized)
        }
    }
}
