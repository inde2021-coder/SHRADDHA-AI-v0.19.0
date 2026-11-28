package `in`.shraddha.ai.operator.website

import java.text.Normalizer
import java.util.Locale

/** Canonical names and spoken aliases taken from the supplied AKKPS Runner screenshots. */
object AkkpsFieldAliasCatalog {
    private val aliases = mapOf(
        "survey number" to "surveyNumber", "survey no" to "surveyNumber", "खसरा नंबर" to "surveyNumber", "सर्वे नंबर" to "surveyNumber",
        "plot number" to "plotNumber", "plot no" to "plotNumber", "प्लॉट नंबर" to "plotNumber",
        "village" to "villageBlockNumber", "village block" to "villageBlockNumber", "गाँव" to "villageBlockNumber", "गांव" to "villageBlockNumber",
        "remarks" to "remarks", "टिप्पणी" to "remarks",
        "total land area" to "totalLandAreaAcres", "total land area acres" to "totalLandAreaAcres", "कुल भूमि क्षेत्र" to "totalLandAreaAcres",
        "paddy area" to "paddyAreaCoveredAcres", "paddy area covered" to "paddyAreaCoveredAcres", "paddy area covered acres" to "paddyAreaCoveredAcres", "धान क्षेत्र" to "paddyAreaCoveredAcres",
        "rice variety" to "riceVariety", "धान की किस्म" to "riceVariety",
        "sowing date" to "sowingDate", "बुवाई की तारीख" to "sowingDate",
        "flowering date" to "floweringDate", "फूल आने की तारीख" to "floweringDate",
        "harvest date" to "harvestDate", "कटाई की तारीख" to "harvestDate",
        "actual yield" to "actualYieldQtlAcre", "actual yield qtl acre" to "actualYieldQtlAcre", "वास्तविक उपज" to "actualYieldQtlAcre",
        "first irrigation date" to "firstIrrigationDate", "पहली सिंचाई की तारीख" to "firstIrrigationDate",
        "drying event dates" to "dryingEventDates", "सूखने की तारीखें" to "dryingEventDates",
        "re flooding dates" to "refloodingDates", "reflooding dates" to "refloodingDates", "पुनः बाढ़ की तारीखें" to "refloodingDates",
        "awd cycles" to "awdCycles", "ए डब्ल्यू डी चक्र" to "awdCycles",
        "max dry down" to "maxDryDownCm", "maximum dry down" to "maxDryDownCm",
        "soil type" to "soilType", "मिट्टी का प्रकार" to "soilType",
        "soil ph" to "soilPh", "मिट्टी का पीएच" to "soilPh",
        "soil organic carbon" to "soilOrganicCarbon", "मिट्टी का जैविक कार्बन" to "soilOrganicCarbon",
        "drainage" to "drainage", "जल निकासी" to "drainage",
        "irrigation source" to "irrigationSource", "सिंचाई का स्रोत" to "irrigationSource",
        "pump type" to "pumpType", "पंप का प्रकार" to "pumpType",
        "pumping hours per season" to "pumpingHoursSeason", "प्रति मौसम पंपिंग घंटे" to "pumpingHoursSeason",
        "electricity kwh" to "electricityKwh", "बिजली यूनिट" to "electricityKwh",
        "visit date" to "visitDate", "दौरे की तारीख" to "visitDate",
        "visitor" to "visitorName", "field visitor" to "visitorName", "निरीक्षक" to "visitorName",
        "cultivation area" to "cultivationArea", "खेती का क्षेत्र" to "cultivationArea",
        "water saved" to "waterSaved", "बचाया गया पानी" to "waterSaved",
        "male laborers" to "maleLaborers", "पुरुष मजदूर" to "maleLaborers",
        "female laborers" to "femaleLaborers", "महिला मजदूर" to "femaleLaborers",
        "farmer name" to "farmerName", "किसान का नाम" to "farmerName", "name" to "farmerName", "नाम" to "farmerName",
        "farmer id" to "farmerId", "farmer code" to "farmerId", "registration no" to "farmerId",
        "registration number" to "farmerId", "किसान आईडी" to "farmerId", "पंजीयन क्रमांक" to "farmerId",
        "पंजीयन संख्या" to "farmerId", "district" to "district", "जिला" to "district",
        "father name" to "fatherName", "father's name" to "fatherName", "पिता का नाम" to "fatherName",
        "mother name" to "motherName", "mother's name" to "motherName", "माता का नाम" to "motherName",
        "phone number" to "phoneNumber", "mobile number" to "phoneNumber", "phone" to "phoneNumber", "मोबाइल नंबर" to "phoneNumber",
        "farmer gender" to "farmerGender", "gender" to "farmerGender", "लिंग" to "farmerGender",
        "identity type" to "identityType", "पहचान का प्रकार" to "identityType",
        "aadhaar number" to "aadhaarNumber", "आधार नंबर" to "aadhaarNumber",
        "address" to "address", "पता" to "address",
        "baseline practice continuous flooding" to "isTheBaselinePracticeContinuousFlooding", "is the baseline practice continuous flooding" to "isTheBaselinePracticeContinuousFlooding", "क्या लगातार बाढ़ सिंचाई होती है" to "isTheBaselinePracticeContinuousFlooding",
        "previous irrigation method" to "previousIrrigationMethod", "पिछली सिंचाई विधि" to "previousIrrigationMethod",
        "kharif season water source" to "kharifWaterSource", "खरीफ जल स्रोत" to "kharifWaterSource",
        "typical water depth" to "typicalWaterDepthCm", "typical water depth cm" to "typicalWaterDepthCm", "सामान्य जल गहराई" to "typicalWaterDepthCm",
        "irrigations per season" to "irrigationsSeason", "प्रति मौसम सिंचाई" to "irrigationsSeason",
        "crop duration" to "cropDurationDays", "फसल अवधि" to "cropDurationDays",
        "year" to "year", "वर्ष" to "year", "season" to "season", "मौसम" to "season",
        "yield" to "yieldQtlAcre", "yield kg acre" to "yieldQtlAcre", "उपज" to "yieldQtlAcre",
        "crop establishment method" to "cropEstablishmentMethod", "फसल स्थापना विधि" to "cropEstablishmentMethod",
        "planting date" to "plantingDate", "रोपाई की तारीख" to "plantingDate",
        "stratum water regime" to "stratumWaterRegime", "जल व्यवस्था" to "stratumWaterRegime",
        "does the plot have an inlet and outlet micro irrigation system" to "doesThePlotHaveAnInletAndOutletMicroIrrigationSystem",
        "inlet and outlet" to "doesThePlotHaveAnInletAndOutletMicroIrrigationSystem",
        "does the farmer want to change from continuous flooding to intermittent flooding" to "doesTheFarmerWantToChangeFromContinuousFloodingToIntermittentFlooding",
        "does the farmer have complete control over the water level in the field" to "doesTheFarmerHaveCompleteControlOverTheWaterLevelInTheField",
        "complete control over the water level in the field" to "doesTheFarmerHaveCompleteControlOverTheWaterLevelInTheField",
        "field water tube used" to "fieldWaterTubeUsed", "खेत में जल ट्यूब" to "fieldWaterTubeUsed",
        "water level at each visit" to "waterLevelAtEachVisit", "हर दौरे पर जल स्तर" to "waterLevelAtEachVisit",
        "max dry down cm below surface" to "maxDryDownCm", "अधिकतम जल-स्तर गिरावट" to "maxDryDownCm",
        "crop loss notes" to "cropLossNotes", "फसल नुकसान विवरण" to "cropLossNotes",
        "soil organic carbon percent" to "soilOrganicCarbon", "मिट्टी जैविक कार्बन प्रतिशत" to "soilOrganicCarbon",
        "field is leveled" to "fieldIsLeveled", "खेत समतल है" to "fieldIsLeveled",
        "organic amendment type" to "organicAmendmentType", "जैविक संशोधन प्रकार" to "organicAmendmentType",
        "organic quantity tons acre" to "organicQuantityTonsAcre",
        "organic application date" to "organicApplicationDate",
        "straw management" to "strawManagement", "पराली प्रबंधन" to "strawManagement",
        "straw quantity tons acre" to "strawQuantityTonsAcre",
        "straw incorporation date" to "strawIncorporationDate",
        "organic straw notes" to "organicStrawNotes",
        "other fertilizers name" to "otherFertilizerName", "other fertilizer name" to "otherFertilizerName", "other fertilizers" to "otherFertilizerName",
        "other fertilizers quantity kg acre" to "otherFertilizerQuantity", "quantity kg acre" to "otherFertilizerQuantity",
        "other fertilizers applications" to "otherFertilizerApplications", "applications" to "otherFertilizerApplications",
        "who decides when to irrigate" to "irrigationDecisionMaker", "irrigation decision maker" to "irrigationDecisionMaker",
        "irrigation decision maker s gender" to "irrigationDecisionMakerSGender",
        "irrigation decision makers gender" to "irrigationDecisionMakerSGender",
        "water source shared with other farmers" to "waterSourceShared",
        "farmer adoption confirmed" to "farmerAdoptionConfirmed", "sensor iot reference" to "sensorIoTReference", "sensor iot" to "sensorIoTReference",
        "verifier notes" to "verifierNotes",
        "cultivation cost inr" to "cultivationCostInr", "water saved percent" to "waterSaved",
        "other gender laborers" to "otherGenderLaborers", "total laborers" to "totalLaborers",
        "pre season water condition" to "preSeasonWaterCondition", "पूर्व मौसम जल स्थिति" to "preSeasonWaterCondition",
        "baseline emission factor source" to "baselineEmissionFactorSource", "आधार उत्सर्जन स्रोत" to "baselineEmissionFactorSource",
        "irrigations season" to "irrigationsSeason", "सिंचाई प्रति मौसम" to "irrigationsSeason",
        "crop duration days" to "cropDurationDays", "फसल अवधि दिन" to "cropDurationDays",
        "historical yields" to "historicalYields", "ऐतिहासिक उपज" to "historicalYields", "fertilizer application dates" to "fertilizerApplicationDates", "urea npk" to "ureaNpk", "field visits audit logs" to "fieldVisitsAuditLogs", "field photos" to "fieldPhotos",
        "plot link" to "plotLink", "add plot" to "plotLink", "प्लॉट जोड़ें" to "plotLink",
        "village block number" to "villageBlockNumber", "गाँव ब्लॉक नंबर" to "villageBlockNumber",
        "signer farmer" to "signerFarmer", "किसान हस्ताक्षरकर्ता" to "signerFarmer",
        "signed date" to "signedDate", "हस्ताक्षर की तारीख" to "signedDate",
        "witness" to "witness", "गवाह" to "witness",
        "aadhaar front" to "aadhaarFront", "aadhaar back" to "aadhaarBack",
        "farmer signature" to "farmerSignature", "agreement front" to "agreementFront",
        "agreement back" to "agreementBack", "land deed page 1" to "landDeedPage1",
        "land deed page 2" to "landDeedPage2", "linked farmers" to "linkedFarmers",
        "fertilizer quantity" to "otherFertilizerQuantity",
        "fertilizer applications" to "otherFertilizerApplications",
        "re-flooding dates" to "refloodingDates", "yield qtl acre" to "yieldQtlAcre"
    )

    fun normalizeLabel(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun canonicalKey(raw: String): String {
        val normalized = normalizeLabel(raw)
        return aliases[normalized] ?: raw.trim()
    }

    fun isKnownAlias(raw: String): Boolean = normalizeLabel(raw) in aliases
}
