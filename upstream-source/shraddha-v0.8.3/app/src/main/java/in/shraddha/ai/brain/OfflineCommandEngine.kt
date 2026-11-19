package `in`.shraddha.ai.brain

/** Offline bilingual intent recognition. It creates proposals only; it never performs device actions. */
object OfflineCommandEngine {
    data class Proposal(val intent: String, val summary: String, val needsPermission: Boolean = true)
    private val rules = listOf(
        listOf("open settings", "settings खोल", "settings खोलो", "settings kholo", "सेटिंग खोल", "सेटिंग्स खोल") to Proposal("open_settings", "Android Settings खोलने का प्रस्ताव"),
        listOf("open camera", "camera खोल", "camera खोलो", "camera kholo", "कैमरा खोल") to Proposal("open_camera", "Camera खोलने का प्रस्ताव"),
        listOf("open whatsapp", "whatsapp खोल", "whatsapp खोलो", "whatsapp kholo", "व्हाट्सएप खोल", "व्हाट्सऐप खोल") to Proposal("open_whatsapp", "WhatsApp खोलने का प्रस्ताव"),
        listOf("open youtube", "यूट्यूब खोल") to Proposal("open_youtube", "YouTube खोलने का प्रस्ताव"),
        listOf("go home", "होम स्क्रीन", "घर स्क्रीन") to Proposal("home", "Home screen पर जाने का प्रस्ताव"),
        listOf("go back", "पीछे जाओ", "वापस जाओ") to Proposal("back", "Back जाने का प्रस्ताव"),
        listOf("turn on flashlight", "टॉर्च चालू") to Proposal("flashlight_on", "Torch चालू करने का प्रस्ताव"),
        listOf("turn off flashlight", "टॉर्च बंद") to Proposal("flashlight_off", "Torch बंद करने का प्रस्ताव"),
        listOf("read screen", "स्क्रीन पढ़ो", "स्क्रीन समझाओ") to Proposal("screen_read", "Screen capture और AI vision अनुमति माँगने का प्रस्ताव"),
        listOf("share screen", "स्क्रीन शेयर") to Proposal("screen_share", "Screen sharing consent माँगने का प्रस्ताव"),
        listOf("call", "कॉल लगाओ", "फोन लगाओ") to Proposal("call_contact", "किसी contact को call करने से पहले contact और confirmation चाहिए"),
        listOf("send message", "मैसेज भेजो", "संदेश भेजो") to Proposal("send_message", "Message भेजने से पहले recipient और final confirmation चाहिए")
    )
    fun recognize(text: String): Proposal? {
        val q = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (q.isBlank() || q.length > 300) return null
        return rules.firstOrNull { (phrases, _) -> phrases.any { q == it || q.startsWith("$it ") } }?.second
    }
}
