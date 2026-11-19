package `in`.shraddha.ai.operator.learning

import `in`.shraddha.ai.operator.ScreenElement

/** Scores semantic UI candidates; low-confidence matches are rejected instead of blind tapping. */
object SemanticMatcher {
    data class Match(val element: ScreenElement, val score: Float)
    fun best(elements: List<ScreenElement>, anchor: SemanticAnchor): Match? {
        val ranked = elements.map { e ->
            var score = 0f
            if (anchor.resourceId.isNotBlank() && e.id == anchor.resourceId) score += .62f
            if (anchor.contentDescription.isNotBlank() && e.description.equals(anchor.contentDescription, true)) score += .30f
            if (anchor.label.isNotBlank() && e.text.equals(anchor.label, true)) score += .28f
            if (anchor.label.isNotBlank() && e.hintText.equals(anchor.label, true)) score += .28f
            else if (anchor.label.isNotBlank() && (e.text.contains(anchor.label, true) || e.description.contains(anchor.label, true))) score += .16f
            if (anchor.className.isNotBlank() && e.className == anchor.className) score += .08f
            if (anchor.packageName.isNotBlank() && e.packageName == anchor.packageName) score += .10f
            if (anchor.role.equals("button", true) && e.clickable) score += .08f
            if (anchor.role.equals("field", true) && e.editable && !e.password) score += .12f
            Match(e, score.coerceAtMost(1f))
        }.sortedByDescending { it.score }
        val first = ranked.firstOrNull() ?: return null
        if (first.score < .48f) return null
        if (ranked.size > 1 && first.score - ranked[1].score < .06f) return null
        return first
    }
}
