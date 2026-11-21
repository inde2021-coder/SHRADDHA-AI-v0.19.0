package com.Lia.assistant.voice

object LiaCapabilityPrompts {
    fun toolInstruction(assistantName: String): String {
        return """
Capabilities & Tools:
You have comprehensive phone automation and on-device capabilities:
1. open_app: Launch any installed application by name.
2. call_contact: Look up contacts and open the dialer. The user must tap Call.
3. message_contact: Prepare SMS/WhatsApp drafts only. Never auto-send; the user must review and tap Send.
4. Screen Navigation & Accessibility: read_screen, tap_text, type_text, scroll_screen, go_back, go_home.
5. WhatsApp: execute_whatsapp_task supports only SEND_MESSAGE drafts. Chat reading/search/mute and social-media publishing are not implemented; never claim these actions happened.
6. build_website: Generate rich, interactive, animated 3D websites in Website Forge when requested.
        """.trimIndent()
    }
}
