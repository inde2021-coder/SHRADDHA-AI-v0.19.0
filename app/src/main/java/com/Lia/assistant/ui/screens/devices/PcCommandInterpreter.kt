package com.Lia.assistant.ui.screens.devices

import org.json.JSONObject

/** Small, allow-listed Hindi/English command parser for the paired Windows companion. */
object PcCommandInterpreter {
    private val secretPattern = Regex("(?i)\\b(password|passcode|otp|one[- ]time code|pin|cvv|recovery phrase)\\b|पासवर्ड|ओटीपी|पिन")

    fun parse(input: String): Result<JSONObject> = runCatching {
        val command = input.trim().replace(Regex("\\s+"), " ")
        require(command.isNotBlank()) { "पहले command लिखें या बोलें।" }
        require(command.length <= 500) { "Command बहुत लंबा है।" }
        val lower = command.lowercase()

        when {
            lower in setOf("home", "desktop", "show desktop", "देस्कटॉप दिखाओ", "डेस्कटॉप दिखाओ", "होम") ->
                JSONObject().put("kind", "home")
            lower in setOf("back", "go back", "पीछे जाओ", "वापस जाओ") ->
                JSONObject().put("kind", "back")
            lower in setOf("scroll up", "ऊपर स्क्रॉल", "ऊपर scroll करो", "ऊपर स्क्रोल करो") ->
                JSONObject().put("kind", "scroll").put("amount", 5)
            lower in setOf("scroll down", "नीचे स्क्रॉल", "नीचे scroll करो", "नीचे स्क्रोल करो") ->
                JSONObject().put("kind", "scroll").put("amount", -5)
            lower in setOf("hardware status", "pc status", "कंप्यूटर की स्थिति") ->
                JSONObject().put("kind", "hardware_status")
            Regex("^(open file|file open|फ़ाइल खोलो|फाइल खोलो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command) != null -> {
                val path = Regex("^(open file|file open|फ़ाइल खोलो|फाइल खोलो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)!!.groupValues[2].trim()
                require(path.length <= 260 && path.matches(Regex("[A-Za-z0-9 _.-]+(?:/[A-Za-z0-9 _.-]+)*")) && path.split("/").none { it == "." || it == ".." }) {
                    "Documents, Downloads या Desktop के अंदर relative file path दें।"
                }
                JSONObject().put("kind", "open_file").put("path", path)
            }
            Regex("^(open folder|open directory|फ़ोल्डर खोलो|फोल्डर खोलो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command) != null -> {
                val path = Regex("^(open folder|open directory|फ़ोल्डर खोलो|फोल्डर खोलो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)!!.groupValues[2].trim()
                require(path.length <= 260 && path.matches(Regex("[A-Za-z0-9 _.-]+(?:/[A-Za-z0-9 _.-]+)*")) && path.split("/").none { it == "." || it == ".." }) {
                    "Documents, Downloads या Desktop के अंदर relative folder path दें।"
                }
                JSONObject().put("kind", "open_folder").put("path", path)
            }
            Regex("^(open|launch|खोलो|खोल)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command) != null -> {
                val app = Regex("^(open|launch|खोलो|खोल)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)!!.groupValues[2].trim()
                require(app.length <= 80 && !app.contains(Regex("[\\\\/:]")) && !app.contains("..")) { "App name मान्य नहीं है।" }
                JSONObject().put("kind", "open_app").put("appName", app)
            }
            Regex("^(type|enter text|लिखो|टाइप करो|टाइप)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command) != null -> {
                val text = Regex("^(type|enter text|लिखो|टाइप करो|टाइप)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)!!.groupValues[2]
                require(text.length <= 500 && !secretPattern.containsMatchIn(text)) { "Password, OTP या निजी सुरक्षा जानकारी टाइप नहीं की जा सकती।" }
                JSONObject().put("kind", "type").put("text", text)
            }
            Regex("^(volume|आवाज़|आवाज)\\s*(?:to|पर)?\\s*(\\d{1,3})$", RegexOption.IGNORE_CASE).find(command) != null -> {
                val value = Regex("^(volume|आवाज़|आवाज)\\s*(?:to|पर)?\\s*(\\d{1,3})$", RegexOption.IGNORE_CASE).find(command)!!.groupValues[2].toInt()
                require(value in 0..100) { "Volume 0 से 100 के बीच रखें।" }
                JSONObject().put("kind", "set_volume").put("percent", value)
            }
            Regex("^(brightness|चमक)\\s*(?:to|पर)?\\s*(\\d{1,3})$", RegexOption.IGNORE_CASE).find(command) != null -> {
                val value = Regex("^(brightness|चमक)\\s*(?:to|पर)?\\s*(\\d{1,3})$", RegexOption.IGNORE_CASE).find(command)!!.groupValues[2].toInt()
                require(value in 0..100) { "Brightness 0 से 100 के बीच रखें।" }
                JSONObject().put("kind", "set_brightness").put("percent", value)
            }
            lower in setOf("read screen", "screen ocr", "ocr", "स्क्रीन पढ़ो", "स्क्रीन का टेक्स्ट पढ़ो", "स्क्रीन से टेक्स्ट निकालो") ->
                JSONObject().put("kind", "ocr")
            Regex("^(click text|click button|press button|बटन दबाओ|टेक्स्ट पर क्लिक करो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command) != null -> {
                val label = Regex("^(click text|click button|press button|बटन दबाओ|टेक्स्ट पर क्लिक करो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)!!.groupValues[2].trim()
                require(label.isNotBlank() && label.length <= 120) { "Button text 1 से 120 characters तक होना चाहिए।" }
                JSONObject().put("kind", "click_text").put("text", label)
            }
            lower in setOf("enter", "press enter", "एंटर") ->
                JSONObject().put("kind", "key_press").put("chord", "enter")
            lower in setOf("escape", "esc", "press escape") ->
                JSONObject().put("kind", "key_press").put("chord", "escape")
            lower in setOf("alt tab", "switch window", "विंडो बदलो") ->
                JSONObject().put("kind", "key_press").put("chord", "alt+tab")
            else -> error("Command समझ नहीं आया। उदाहरण: Open Notepad, desktop, scroll down, type नमस्ते, volume 40।")
        }
    }
}
