package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsLocalCommonFieldMemoryTest {
    @Test
    fun onlyStableAdministrativeDefaultsAreReusable() {
        assertTrue(AkkpsLocalCommonFieldMemory.isReusableCommonField("District"))
        assertTrue(AkkpsLocalCommonFieldMemory.isReusableCommonField("जिला"))
        assertTrue(AkkpsLocalCommonFieldMemory.isReusableCommonField("Block"))
        assertTrue(AkkpsLocalCommonFieldMemory.isReusableCommonField("तहसील"))
        assertFalse(AkkpsLocalCommonFieldMemory.isReusableCommonField("Farmer Name"))
        assertFalse(AkkpsLocalCommonFieldMemory.isReusableCommonField("Phone Number"))
        assertFalse(AkkpsLocalCommonFieldMemory.isReusableCommonField("Survey Number"))
        assertFalse(AkkpsLocalCommonFieldMemory.isReusableCommonField("Area"))
    }

    @Test
    fun normalizationHandlesHindiAndPunctuation() {
        assertTrue(
            AkkpsLocalCommonFieldMemory.isReusableCommonField("  जिला  ")
        )
        assertTrue(
            AkkpsLocalCommonFieldMemory.isReusableCommonField("विकास-खंड")
        )
    }
}
