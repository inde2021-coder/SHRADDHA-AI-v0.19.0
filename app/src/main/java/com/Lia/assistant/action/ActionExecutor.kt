package com.Lia.assistant.action

import android.content.Context
import com.Lia.assistant.agent.NovaAccessibilityService
import com.Lia.assistant.license.AccessKeyManager
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
                        result.put("status", "success")
                        result.put("action", callStatus)
                        result.put("contact", lookup.displayName)
                        result.put("number", lookup.phoneNumber)
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

                val lookup = ContactResolver.lookup(context, contactName)
                val phoneNumber = when (lookup) {
                    is ContactLookupResult.Found -> lookup.phoneNumber
                    ContactLookupResult.NoPermission -> {
                        result.put("status", "error")
                        result.put("action", "contacts_permission_required")
                        result.put("message", "Contacts permission is required to resolve this name. You can provide a full phone number instead.")
                        return result
                    }
                    ContactLookupResult.NotFound -> {
                        if (ContactResolver.isDirectPhoneNumber(contactName)) contactName else {
                            result.put("status", "error")
                            result.put("action", "recipient_not_found")
                            result.put("message", "Recipient was not found in contacts. Provide a full phone number.")
                            return result
                        }
                    }
                }

                val isWhatsApp = targetApp == "whatsapp"
                val composerStatus = if (isWhatsApp) {
                    CommIntents.openWhatsAppComposer(context, phoneNumber, messageText)
                } else {
                    CommIntents.openSmsComposer(context, phoneNumber, messageText)
                }

                // Never auto-press Send. The user reviews the recipient and message in the target app.
                result.put("status", if (composerStatus == "composer_opened") "pending_user_confirmation" else "error")
                result.put("action", composerStatus)
                result.put("recipient", phoneNumber)
                result.put("message", if (composerStatus == "composer_opened") "Draft opened. Review it and tap Send yourself; nothing has been sent by Shraddha AI." else "Could not open the message composer.")
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

            "execute_social_media_task", "social_media_task_control" -> {
                result.put("status", "unsupported")
                result.put("action", "social_publishing_not_implemented")
                result.put("message", "Social publishing automation is not implemented in this build. No post or task was created.")
            }

            "execute_whatsapp_task" -> {
                val action = args.optString("action", "").uppercase()
                if (action != "SEND_MESSAGE") {
                    result.put("status", "unsupported")
                    result.put("action", "whatsapp_action_not_implemented")
                    result.put("message", "Only SEND_MESSAGE draft preparation is supported. Reading/searching chats and chat management are not implemented.")
                } else {
                    val recipient = args.optString("phone_number").ifBlank { args.optString("contact_name") }.trim()
                    val message = args.optString("message").trim()
                    if (recipient.isBlank() || message.isBlank()) {
                        result.put("status", "error")
                        result.put("message", "A recipient and message body are required.")
                    } else {
                        val lookup = ContactResolver.lookup(context, recipient)
                        val number = when (lookup) {
                            is ContactLookupResult.Found -> lookup.phoneNumber
                            ContactLookupResult.NoPermission -> if (ContactResolver.isDirectPhoneNumber(recipient)) recipient else ""
                            ContactLookupResult.NotFound -> if (ContactResolver.isDirectPhoneNumber(recipient)) recipient else ""
                        }
                        if (number.isBlank()) {
                            result.put("status", "error")
                            result.put("action", "recipient_not_found")
                            result.put("message", "Provide a full phone number or grant Contacts permission.")
                        } else {
                            val opened = CommIntents.openWhatsAppComposer(context, number, message)
                            result.put("status", if (opened == "composer_opened") "pending_user_confirmation" else "error")
                            result.put("action", opened)
                            result.put("recipient", number)
                            result.put("message", "Review the WhatsApp draft and tap Send yourself. No message was sent automatically.")
                        }
                    }
                }
            }

            "whatsapp_task_control" -> {
                result.put("status", "unsupported")
                result.put("action", "manual_confirmation_only")
                result.put("message", "Shraddha AI will not auto-send or auto-confirm messages. Review and send the draft inside WhatsApp.")
            }

            else -> {
                result.put("error", "Unknown tool: $toolName")
            }
        }

        return result
    }
}
