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
            "Village / Block Number" to "villageBlockNumber",
            "Is the baseline practice continuous flooding?" to "isTheBaselinePracticeContinuousFlooding",
            "Paddy Area Covered (acres)" to "paddyAreaCoveredAcres",
            "Actual Yield (qtl/acre)" to "actualYieldQtlAcre",
            "Pumping Hours / Season" to "pumpingHoursSeason",
            "Total Land Area (acres)" to "totalLandAreaAcres",
            "Soil Organic Carbon (%)" to "soilOrganicCarbon",
            "Urea (NPK)" to "ureaNpk",
            "Fertilizer Application Dates" to "fertilizerApplicationDates",
            "Sensor / IoT Reference" to "sensorIoTReference",
            "Field Visits / Audit Logs" to "fieldVisitsAuditLogs",
            "Cultivation Cost (INR)" to "cultivationCostInr",
            "Water Saved (%)" to "waterSaved",
            "Male Laborers" to "maleLaborers",
            "Female Laborers" to "femaleLaborers",
            "Other Gender Laborers" to "otherGenderLaborers",
            "Total Laborers" to "totalLaborers",
            "Organic Quantity (tons/acre)" to "organicQuantityTonsAcre",
            "Straw Quantity (tons/acre)" to "strawQuantityTonsAcre",
            "Does the plot have an Inlet and outlet / micro irrigation system?" to "doesThePlotHaveAnInletAndOutletMicroIrrigationSystem",
            "Does the farmer want to change from continuous flooding to intermittent flooding?" to "doesTheFarmerWantToChangeFromContinuousFloodingToIntermittentFlooding",
            "Does the farmer have complete control over the water level in the field?" to "doesTheFarmerHaveCompleteControlOverTheWaterLevelInTheField",
            "Pre-season Water Condition" to "preSeasonWaterCondition",
            "Baseline Emission Factor Source" to "baselineEmissionFactorSource",
            "Typical Water Depth (cm)" to "typicalWaterDepthCm",
            "Yield (qtl/acre)" to "yieldQtlAcre",
            "Irrigation decision maker's gender" to "irrigationDecisionMakerSGender",
            "Crop Duration (days)" to "cropDurationDays",
            "Drying Event Dates" to "dryingEventDates",
            "Re-flooding Dates" to "refloodingDates",
            "Field Water Tube Used" to "fieldWaterTubeUsed",
            "Organic Amendment Type" to "organicAmendmentType",
            "Who decides when to irrigate?" to "irrigationDecisionMaker",
            "Farmer Adoption Confirmed" to "farmerAdoptionConfirmed",
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
