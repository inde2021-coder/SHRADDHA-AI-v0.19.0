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

    @Test fun suppliedFarmerPlotAndAwdScreenshotsHaveCanonicalFieldAliases() {
        val expected = mapOf(
            "Crop Establishment Method" to "cropEstablishmentMethod",
            "Planting Date" to "plantingDate",
            "Pre-season Water Condition" to "preSeasonWaterCondition",
            "Baseline Emission Factor Source" to "baselineEmissionFactorSource",
            "Typical Water Depth (cm)" to "typicalWaterDepthCm",
            "Crop Duration (days)" to "cropDurationDays",
            "Drying Event Dates" to "dryingEventDates",
            "Re-flooding Dates" to "refloodingDates",
            "Field Water Tube Used" to "fieldWaterTubeUsed",
            "Organic Amendment Type" to "organicAmendmentType",
            "Fertilizer Application Dates" to "fertilizerApplicationDates",
            "Who decides when to irrigate?" to "irrigationDecisionMaker",
            "Farmer Adoption Confirmed" to "farmerAdoptionConfirmed",
            "Field Visits / Audit Logs" to "fieldVisitsAuditLogs",
            "Cultivation Cost (INR)" to "cultivationCostInr",
            "Other Gender Laborers" to "otherGenderLaborers",
            "Signer (farmer)" to "signerFarmer",
            "Signed date" to "signedDate",
            "Witness" to "witness",
            "Land Deed Page 1" to "landDeedPage1"
        )
        expected.forEach { (label, canonical) ->
            assertEquals(label, canonical, AkkpsFieldAliasCatalog.canonicalKey(label))
        }
    }

    @Test fun normalisedLabelsAreRecognisedWithoutChangingUnknownFields() {
        assertTrue(AkkpsFieldAliasCatalog.isKnownAlias("  FARMER   CODE "))
        assertEquals("Custom Field", AkkpsFieldAliasCatalog.canonicalKey("Custom Field"))
    }
}
