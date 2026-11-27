package `in`.shraddha.ai.operator.website

/** Canonical names and spoken aliases taken from the supplied AKKPS Runner screenshots. */
object AkkpsFieldAliasCatalog {
    private val aliases = mapOf(
        "survey number" to "surveyNumber", "survey no" to "surveyNumber", "खसरा नंबर" to "surveyNumber",
        "plot number" to "plotNumber", "plot no" to "plotNumber", "प्लॉट नंबर" to "plotNumber",
        "village" to "village", "village block" to "village", "गाँव" to "village", "गांव" to "village",
        "remarks" to "remarks", "टिप्पणी" to "remarks",
        "total land area" to "totalLandAreaAcres", "कुल भूमि क्षेत्र" to "totalLandAreaAcres",
        "paddy area" to "paddyAreaAcres", "धान क्षेत्र" to "paddyAreaAcres",
        "rice variety" to "riceVariety", "धान की किस्म" to "riceVariety",
        "sowing date" to "sowingDate", "बुवाई की तारीख" to "sowingDate",
        "flowering date" to "floweringDate", "फूल आने की तारीख" to "floweringDate",
        "harvest date" to "harvestDate", "कटाई की तारीख" to "harvestDate",
        "actual yield" to "actualYield", "वास्तविक उपज" to "actualYield",
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
        "pumping hours per season" to "pumpingHoursPerSeason", "प्रति मौसम पंपिंग घंटे" to "pumpingHoursPerSeason",
        "electricity kwh" to "electricityKwh", "बिजली यूनिट" to "electricityKwh",
        "visit date" to "visitDate", "दौरे की तारीख" to "visitDate",
        "visitor" to "visitorName", "field visitor" to "visitorName", "निरीक्षक" to "visitorName",
        "cultivation area" to "cultivationArea", "खेती का क्षेत्र" to "cultivationArea",
        "water saved" to "waterSaved", "बचाया गया पानी" to "waterSaved",
        "male laborers" to "maleLaborers", "पुरुष मजदूर" to "maleLaborers",
        "female laborers" to "femaleLaborers", "महिला मजदूर" to "femaleLaborers"
    )

    fun canonicalKey(raw: String): String {
        val normalized = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        return aliases[normalized] ?: raw.trim()
    }

    fun isKnownAlias(raw: String): Boolean =
        raw.trim().lowercase().replace(Regex("\\s+"), " ") in aliases
}
