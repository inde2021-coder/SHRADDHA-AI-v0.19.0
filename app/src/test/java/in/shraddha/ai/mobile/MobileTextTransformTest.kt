package `in`.shraddha.ai.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileTextTransformTest {
    @Test fun trimRemovesOnlyOuterWhitespace() {
        assertEquals("hello  world", MobileTextTransform.apply("  hello  world  ", MobileTextTransform.Operation.TRIM))
    }

    @Test fun caseConversionUsesStableLocaleIndependentRules() {
        assertEquals("MIXED I", MobileTextTransform.apply("Mixed I", MobileTextTransform.Operation.UPPERCASE))
        assertEquals("mixed i", MobileTextTransform.apply("MIXED I", MobileTextTransform.Operation.LOWERCASE))
    }

    @Test fun collapseWhitespaceHandlesTabsAndNewlines() {
        assertEquals("one two three", MobileTextTransform.apply(" one\t two\n three ", MobileTextTransform.Operation.COLLAPSE_WHITESPACE))
    }

    @Test fun removeBlankLinesPreservesNonblankLineContents() {
        assertEquals(" first \nsecond", MobileTextTransform.apply(" first \n\nsecond\n", MobileTextTransform.Operation.REMOVE_BLANK_LINES))
    }

    @Test fun emptyInputStaysEmpty() {
        MobileTextTransform.Operation.values().forEach { operation ->
            assertEquals("", MobileTextTransform.apply("", operation))
        }
    }
}
