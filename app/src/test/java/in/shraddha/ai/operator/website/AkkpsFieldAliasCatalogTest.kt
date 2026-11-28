package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsFieldAliasCatalogTest {
    @Test fun farmerIdentityAndDistrictLabelsMapToCanonicalAkKpsFields() {
        assertEquals("farmerName", AkkpsFieldAliasCatalog.canonicalKey("Farmer Name"))
        assertEquals("farmerName", AkkpsFieldAliasCatalog.canonicalKey("नाम"))
        assertEquals("farmerId", AkkpsFieldAliasCatalog.canonicalKey("Farmer ID"))
        assertEquals("farmerId", AkkpsFieldAliasCatalog.canonicalKey("पंजीयन क्रमांक"))
        assertEquals("district", AkkpsFieldAliasCatalog.canonicalKey("District"))
        assertEquals("district", AkkpsFieldAliasCatalog.canonicalKey("जिला"))
    }

    @Test fun normalisedLabelsAreRecognisedWithoutChangingUnknownFields() {
        assertTrue(AkkpsFieldAliasCatalog.isKnownAlias("  FARMER   CODE "))
        assertEquals("Custom Field", AkkpsFieldAliasCatalog.canonicalKey("Custom Field"))
    }
}
