package `in`.shraddha.ai.operator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperatorTargetResolutionPolicyTest {
    private data class Target(val id: String, val text: String, val description: String = "")

    private fun resolve(query: String, vararg targets: Target) =
        OperatorTargetResolutionPolicy.selectUnique(query, targets.toList()) { listOf(it.text, it.description) }

    @Test fun exactMatchWinsOverOtherPartialMatches() {
        val exact = Target("exact", "Save")
        val partial = Target("partial", "Save draft")
        assertEquals("exact", resolve("Save", exact, partial)?.id)
    }

    @Test fun rejectsAmbiguousExactLabels() {
        assertNull(resolve("Save", Target("one", "Save"), Target("two", "Save")))
    }

    @Test fun permitsOneUnambiguousPartialMatch() {
        assertEquals("one", resolve("Save", Target("one", "Save changes"), Target("two", "Cancel"))?.id)
    }

    @Test fun rejectsAmbiguousPartialMatches() {
        assertNull(resolve("Save", Target("one", "Save draft"), Target("two", "Save changes")))
    }

    @Test fun normalizesUnicodeAndPunctuationForExactMatching() {
        assertEquals("one", resolve("Ｐａｙ—Now", Target("one", "Pay Now"))?.id)
    }

    @Test fun rejectsBlankAndUnmatchedTargets() {
        assertNull(resolve(" ", Target("one", "Save")))
        assertNull(resolve("Delete", Target("one", "Save")))
    }
}
