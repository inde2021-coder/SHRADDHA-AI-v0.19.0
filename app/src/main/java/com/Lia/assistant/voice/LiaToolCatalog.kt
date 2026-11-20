package com.Lia.assistant.voice

import org.json.JSONArray

object LiaToolCatalog {
    fun declarations(): JSONArray {
        val array = JSONArray()

        // Base tools
        array.put(
            GeminiToolUtils.functionDecl(
                name = "open_app",
                description = "Opens an installed application on the phone by its name.",
                params = mapOf("app_name" to "The name of the app to launch (e.g. YouTube, WhatsApp, Instagram)"),
                required = listOf("app_name")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "call_contact",
                description = "Opens the phone dialer with the selected contact/number. The user must review and tap Call.",
                params = mapOf("contact_name" to "The contact name or phone number to call"),
                required = listOf("contact_name")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "message_contact",
                description = "Prepares a draft in SMS or WhatsApp. Never sends automatically; the user reviews and taps Send.",
                params = mapOf(
                    "contact_name" to "Name or phone number of the recipient",
                    "message" to "Text body of the message",
                    "app" to "Target app: 'whatsapp' or 'sms' (defaults to 'sms')"
                ),
                required = listOf("contact_name", "message")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "build_website",
                description = "Launches the Website Forge to design, code, and animate a complete website based on a description.",
                params = mapOf("prompt" to "Detailed description of the website to build"),
                required = listOf("prompt")
            )
        )

        // Screen accessibility tools
        array.put(
            GeminiToolUtils.functionDecl(
                name = "read_screen",
                description = "Inspects and returns visible elements, labels, and text on the current screen."
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "tap_text",
                description = "Taps a visible UI element containing the specified text or label.",
                params = mapOf("text" to "Text or label of the button or element to tap"),
                required = listOf("text")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "type_text",
                description = "Types text into the currently focused or active input field.",
                params = mapOf("text" to "Text to type into the active field"),
                required = listOf("text")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "scroll_screen",
                description = "Scrolls the current screen forward or backward.",
                params = mapOf("direction" to "Direction to scroll: 'forward' (down) or 'backward' (up)"),
                required = listOf("direction")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "go_back",
                description = "Performs the system Back action on the phone."
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "go_home",
                description = "Performs the system Home action on the phone."
            )
        )

        // Visual Agent Tools
        array.put(
            GeminiToolUtils.functionDecl(
                name = "execute_social_media_task",
                description = "Navigates and executes actions on Instagram or Facebook. Always requests confirmation before publishing irreversible content.",
                params = mapOf(
                    "platform" to "Platform: 'INSTAGRAM' or 'FACEBOOK'",
                    "action" to "Action: 'CREATE_POST', 'CREATE_REEL', 'CREATE_STORY', or 'TEXT_POST'",
                    "media_uri" to "Optional local content:// URI of the media to share",
                    "caption" to "Caption or text content for the post",
                    "mode" to "'DRAFT' (prepare only) or 'PUBLISH' (requires explicit confirmation before final publish)",
                    "target_account" to "Optional account handle or name"
                ),
                required = listOf("platform", "action")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "social_media_task_control",
                description = "Controls an active social media task. Only call command 'CONFIRM' after the user explicitly said yes.",
                params = mapOf(
                    "command" to "Control command: 'CONFIRM', 'REJECT', 'RESUME', 'CANCEL', or 'STATUS'",
                    "task_id" to "Task ID returned from execute_social_media_task"
                ),
                required = listOf("command", "task_id")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "execute_whatsapp_task",
                description = "Prepares a WhatsApp message draft only. Chat reading, search, and mute automation are not available.",
                params = mapOf(
                    "action" to "Only supported action: 'SEND_MESSAGE' (opens a draft; user must tap Send)",
                    "contact_name" to "Name of contact",
                    "phone_number" to "Optional phone number",
                    "message" to "Message body if sending",
                    "query" to "Search query if searching"
                ),
                required = listOf("action")
            )
        )

        array.put(
            GeminiToolUtils.functionDecl(
                name = "whatsapp_task_control",
                description = "Controls or confirms an active WhatsApp task.",
                params = mapOf(
                    "command" to "Control command: 'CONFIRM', 'REJECT', 'CANCEL', or 'STATUS'",
                    "task_id" to "Task ID"
                ),
                required = listOf("command", "task_id")
            )
        )

        return array
    }
}
