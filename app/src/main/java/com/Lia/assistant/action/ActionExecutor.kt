package com.Lia.assistant.action

import android.content.Context
import com.Lia.assistant.agent.NovaAccessibilityService
import com.Lia.assistant.license.AccessKeyManager
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

object ActionExecutor {

    suspend fun execute(context: Context, toolName: String, args: JSONObject): JSONObject {
        val result = JSONObject()

        // Licensing check
        if (!AccessKeyManager.isAccessGranted()) {
            result.put("status", "error")
            result.put("action", "license_required")
            result.put("message", "A valid access key is required to use this direct capability. Please enter your access key in Profile Settings.")
            return result
        }

        // Draft-only messaging does not auto-send, but sensitive credentials must
        // never be placed into a message composer by a voice/tool instruction.
        if (toolName in setOf("message_contact", "execute_whatsapp_task")) {
            val draftText = args.optString("message", "")
            val draftDecision = OperatorSafetyPolicy.assess("TYPE_TEXT", value = draftText)
            if (draftDecision.risk == OperatorSafetyPolicy.Risk.BLOCK) {
                result.put("status", "error")
                result.put("action", "blocked_by_safety_policy")
                result.put("message", draftDecision.reason + " Message draft was not opened.")
                return result
            }
        }

        // Direct voice/tool actions may not bypass the Operator safety policy.
        // Message tools only open a draft; the user still taps Send in the target app.
        if (toolName !in setOf("message_contact", "execute_whatsapp_task")) {
            val target = args.optString("text", args.optString("app_name", args.optString("contact_name", "")))
            val value = args.optString("text", args.optString("message", ""))
            val decision = OperatorSafetyPolicy.assess(toolName, target, value)
            if (decision.risk != OperatorSafetyPolicy.Risk.LOW) {
                result.put("status", "error")
                result.put("action", if (decision.risk == OperatorSafetyPolicy.Risk.BLOCK) "blocked_by_safety_policy" else "confirmation_required")
                result.put("message", decision.reason + " Direct voice action stopped; open App Operator to review and confirm.")
                return result
            }
        }

        when (toolName) {
            "open_app" -> {
                val appName = args.optString("app_name", "").trim()
                if (appName.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "App name cannot be empty")
                    return result
                }

                val apps = InstalledAppLabelCache.get(context)
                val packageName = AppLauncher.findApp(appName, apps)

                if (packageName != null) {
                    val launched = AppLauncher.launch(context, packageName)
                    if (launched) {
                        result.put("status", "success")
                        result.put("action", "app_opened")
                        result.put("package", packageName)
                    } else {
                        result.put("status", "error")
                        result.put("action", "launch_failed")
                    }
                } else {
                    result.put("status", "error")
                    result.put("action", "app_not_found")
                    result.put("query", appName)
                }
            }

            "call_contact" -> {
                val query = args.optString("contact_name", "").trim()
                if (query.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "Contact query is empty")
                    return result
                }

                when (val lookup = ContactResolver.lookup(context, query)) {
                    is ContactLookupResult.Found -> {
                        val callStatus = CommIntents.placeCall(context, lookup.phoneNumber)
                        result.put("status", if (callStatus == "dialer_opened") "success" else "error")
                        result.put("action", callStatus)
                        result.put("contact", lookup.displayName)
                        result.put("number", lookup.phoneNumber)
                        result.put("message", "Dialer opened. Review the number and tap Call to place the call.")
                    }
                    is ContactLookupResult.NoPermission -> {
                        result.put("status", "error")
                        result.put("action", "no_contacts_permission")
                        result.put("message", "Please grant Contacts permission in Settings.")
                    }
                    is ContactLookupResult.NotFound -> {
                        result.put("status", "error")
                        result.put("action", "contact_not_found")
                        result.put("query", query)
                    }
                }
            }

            "message_contact" -> {
                val contactName = args.optString("contact_name", "").trim()
                val messageText = args.optString("message", "").trim()
                val targetApp = args.optString("app", "sms").lowercase().trim()

                if (contactName.isEmpty() || messageText.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "Recipient and message body are required")
                    return result
                }

                if (messageText.length > 2_000) {
                    result.put("status", "error")
                    result.put("message", "Message is too long (maximum 2,000 characters).")
                    return result
                }
                if (targetApp !in setOf("sms", "whatsapp")) {
                    result.put("status", "error")
                    result.put("message", "Choose SMS or WhatsApp.")
                    return result
                }
                val lookup = ContactResolver.lookup(context, contactName)
                val found = lookup as? ContactLookupResult.Found
                if (found == null) {
                    result.put("status", "error")
                    result.put("action", if (lookup is ContactLookupResult.NoPermission) "contacts_permission_required" else "contact_not_found")
                    result.put("message", if (lookup is ContactLookupResult.NoPermission)
                        "Control & Privacy में Allow Contact Lookup चालू करें, या सीधे phone number बोलें."
                    else "Contact नहीं मिला। नाम जाँचें या पूरा phone number दें.")
                    return result
                }
                val draftStatus = if (targetApp == "whatsapp") {
                    CommIntents.openWhatsAppComposer(context, found.phoneNumber, messageText)
                } else {
                    CommIntents.openSmsComposer(context, found.phoneNumber, messageText)
                }
                result.put("status", if (draftStatus == "composer_opened") "success" else "error")
                result.put("action", if (draftStatus == "composer_opened") "draft_ready" else draftStatus)
                result.put("recipient", found.displayName)
                result.put("message", if (draftStatus == "composer_opened")
                    "Draft opened. Review recipient and message, then tap Send yourself. Shraddha never auto-sends."
                else "Message composer could not be opened.")
            }

            "read_screen" -> {
                val service = NovaAccessibilityService.instance
                if (service == null) {
                    result.put("status", "error")
                    result.put("action", "accessibility_not_enabled")
                    result.put("message", "Accessibility Service is not enabled. Please enable Lia AI in Accessibility Settings.")
                } else {
                    val elements = service.readScreen()
                    result.put("status", "success")
                    result.put("action", "screen_read")
                    val array = JSONArray()
                    elements.forEach { array.put(it) }
                    result.put("elements", array)
                }
            }

            "tap_text" -> {
                val service = NovaAccessibilityService.instance
                val text = args.optString("text", "").trim()
                if (service == null) {
                    result.put("status", "error")
                    result.put("action", "accessibility_not_enabled")
                } else if (text.isEmpty()) {
                    result.put("status", "error")
                    result.put("message", "Target text is empty")
                } else {
                    val res = service.tapText(text)
                    result.put("status", if (res == "tapped") "success" else "error")
                    result.put("action", res)
                    result.put("target", text)
                }
            }

            "type_text" -> {
                val service = NovaAccessibilityService.instance
                val text = args.optString("text", "")
                if (service == null) {
                    result.put("status", "error")
                    result.put("action", "accessibility_not_enabled")
                } else {
                    val res = service.typeText(text)
                    result.put("status", if (res == "typed") "success" else "error")
                    result.put("action", res)
                }
            }

            "scroll_screen" -> {
                val service = NovaAccessibilityService.instance
                val direction = args.optString("direction", "forward")
                if (service == null) {
                    result.put("status", "error")
                    result.put("action", "accessibility_not_enabled")
                } else {
                    val res = service.scroll(direction)
                    result.put("status", if (res == "scrolled") "success" else "error")
                    result.put("action", res)
                }
            }

            "go_back" -> {
                val service = NovaAccessibilityService.instance
                if (service == null) {
                    result.put("status", "error")
                    result.put("action", "accessibility_not_enabled")
                } else {
                    val res = service.performBack()
                    result.put("status", if (res == "done") "success" else "error")
                    result.put("action", res)
                }
            }

            "go_home" -> {
                val service = NovaAccessibilityService.instance
                if (service == null) {
                    result.put("status", "error")
                    result.put("action", "accessibility_not_enabled")
                } else {
                    val res = service.performHome()
                    result.put("status", if (res == "done") "success" else "error")
                    result.put("action", res)
                }
            }

            "execute_social_media_task" -> {
                val content = args.optString("content", args.optString("text", args.optString("caption", ""))).trim()
                if (content.isBlank() || content.length > 4_000) {
                    result.put("status", "error")
                    result.put("action", "invalid_share_content")
                    result.put("message", "Share text must contain 1-4,000 characters.")
                } else {
                    // Open the Android Sharesheet only. The user chooses the social app,
                    // reviews the content, and performs the final publish action themselves.
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, content)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(share, "Review and choose where to share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onSuccess {
                        result.put("status", "success")
                        result.put("action", "share_draft_opened")
                        result.put("message", "Share draft opened. Choose the app, review it, and tap Publish/Send yourself. Shraddha does not auto-publish.")
                    }.onFailure {
                        result.put("status", "error")
                        result.put("action", "share_sheet_unavailable")
                        result.put("message", "Android could not open the share sheet.")
                    }
                }
            }

            "social_media_task_control" -> {
                result.put("status", "error")
                result.put("action", "not_implemented")
                result.put("message", "Scheduled publishing, analytics, comments and account management are not connected. No background social task was started.")
            }

            "execute_whatsapp_task" -> {
                val action = args.optString("action", "").uppercase()
                if (action != "SEND_MESSAGE") {
                    result.put("status", "error")
                    result.put("action", "unsupported_whatsapp_action")
                    result.put("message", "Only WhatsApp message drafts are supported. Chat reading, search and mute controls are not implemented.")
                } else {
                    val contact = args.optString("contact_name", "").trim()
                    val message = args.optString("message", "").trim()
                    val lookup = ContactResolver.lookup(context, contact)
                    val found = lookup as? ContactLookupResult.Found
                    if (contact.isBlank() || message.isBlank() || message.length > 2_000 || found == null) {
                        result.put("status", "error")
                        result.put("action", if (lookup is ContactLookupResult.NoPermission) "contacts_permission_required" else "invalid_recipient_or_message")
                    } else {
                        val draft = CommIntents.openWhatsAppComposer(context, found.phoneNumber, message)
                        result.put("status", if (draft == "composer_opened") "success" else "error")
                        result.put("action", if (draft == "composer_opened") "draft_ready" else draft)
                        result.put("recipient", found.displayName)
                        result.put("message", "Draft only. Review and tap Send in WhatsApp; Shraddha will not send automatically.")
                    }
                }
            }

            "whatsapp_task_control" -> {
                result.put("status", "error")
                result.put("action", "not_implemented")
                result.put("message", "There is no active WhatsApp automation task in this build.")
            }

            else -> {
                result.put("error", "Unknown tool: $toolName")
            }
        }

        return result
    }
}
