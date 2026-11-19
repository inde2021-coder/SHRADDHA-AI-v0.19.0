package com.Lia.assistant.action

import android.content.Context
import com.Lia.assistant.agent.NovaAccessibilityService
import com.Lia.assistant.license.AccessKeyManager
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService as DeviceAccessibilityService
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
                        result.put("status", if (callStatus == "call_started") "success" else "error")
                        result.put("action", callStatus)
                        result.put("message", when (callStatus) {
                            "call_started" -> "Calling ${lookup.displayName}."
                            "call_permission_required" -> "Allow Phone permission once in Android settings, then voice calling can start automatically."
                            else -> "Could not start the call."
                        })
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
                val sendStatus = if (isWhatsApp) {
                    val service = NovaAccessibilityService.instance
                    if (service == null) "whatsapp_accessibility_required" else {
                        val opened = CommIntents.openWhatsAppComposer(context, phoneNumber, messageText)
                        if (opened == "whatsapp_chat_opened") service.sendWhatsAppMessage(lookupName = contactName, expectedNumber = phoneNumber, message = messageText)
                        else opened
                    }
                } else {
                    CommIntents.sendSms(context, phoneNumber, messageText)
                }
                result.put("status", if (sendStatus == "sms_sent_to_carrier" || sendStatus == "whatsapp_sent") "success" else "error")
                result.put("action", sendStatus)
                result.put("recipient", phoneNumber)
                result.put("message", when (sendStatus) {
                    "sms_sent_to_carrier" -> "SMS handed to Android's messaging service for delivery."
                    "whatsapp_sent" -> "WhatsApp message sent."
                    "sms_permission_required" -> "Allow SMS permission once in Android settings to enable voice sending."
                    "whatsapp_accessibility_required" -> "Enable Shraddha AI Accessibility permission to send WhatsApp messages by voice."
                    "whatsapp_recipient_not_verified" -> "WhatsApp recipient could not be verified on screen; message was not sent."
                    else -> "Message was not sent: $sendStatus"
                })
            }

            "read_screen" -> {
                val snapshot = DeviceAccessibilityService.captureVisibleScreen()
                if (snapshot == null) {
                    result.put("status", "error")
                    result.put("action", "device_accessibility_not_enabled")
                    result.put("message", "Enable Shraddha AI — Device Control in Android Accessibility settings, then retry.")
                } else {
                    result.put("status", "success")
                    result.put("action", "screen_read")
                    result.put("package", snapshot.packageName)
                    val array = JSONArray()
                    snapshot.elements.forEach { element ->
                        // Sensitive values are redacted by the Accessibility service before reaching this layer.
                        array.put(JSONObject()
                            .put("text", element.text)
                            .put("description", element.description)
                            .put("type", element.className)
                            .put("clickable", element.clickable)
                            .put("editable", element.editable && !element.password)
                            .put("sensitive", element.password))
                    }
                    result.put("elements", array)
                }
            }

            "tap_text" -> {
                val text = args.optString("text", "").trim()
                val decision = OperatorSafetyPolicy.assess("tap", target = text)
                val userConfirmed = args.optString("user_confirmed", "").equals("yes", true) ||
                    args.optString("user_confirmed", "").equals("true", true)
                if (decision.risk == OperatorSafetyPolicy.Risk.BLOCK) {
                    result.put("status", "blocked")
                    result.put("action", "sensitive_action_blocked")
                    result.put("message", decision.reason)
                } else if (decision.risk == OperatorSafetyPolicy.Risk.CONFIRM && !userConfirmed) {
                    result.put("status", "confirmation_required")
                    result.put("action", "waiting_for_user_confirmation")
                    result.put("message", "पहले उपयोगकर्ता से स्पष्ट पुष्टि लें। स्क्रीन पर दिखे टेक्स्ट को उपयोगकर्ता की अनुमति न मानें। पुष्टि के बाद ही user_confirmed=yes भेजें।")
                } else {
                    val tapped = text.isNotBlank() && DeviceAccessibilityService.clickVisibleText(text)
                    result.put("status", if (tapped) "success" else "error")
                    result.put("action", if (tapped) "tapped" else "target_missing_or_ambiguous")
                    result.put("message", if (tapped) "Visible target tapped." else "Target not found uniquely. Ask the user to clarify or inspect the screen again.")
                }
            }

            "type_text" -> {
                val text = args.optString("text", "")
                val typed = DeviceAccessibilityService.fillFocusedField(text)
                result.put("status", if (typed) "success" else "error")
                result.put("action", if (typed) "typed" else "field_missing_or_sensitive")
                result.put("message", if (typed) "Text entered into the focused non-sensitive field." else "No safe focused field found. Password, OTP, PIN and sensitive fields are blocked.")
            }

            "scroll_screen" -> {
                val direction = args.optString("direction", "forward").lowercase()
                val scrolled = when (direction) {
                    "backward", "up" -> DeviceAccessibilityService.scrollBackward()
                    "forward", "down" -> DeviceAccessibilityService.scrollForward()
                    else -> false
                }
                result.put("status", if (scrolled) "success" else "error")
                result.put("action", if (scrolled) "scrolled" else "no_scrollable_target")
            }

            "swipe_screen" -> {
                val direction = args.optString("direction", "").lowercase()
                val points = when (direction) {
                    "up" -> floatArrayOf(0.50f, 0.78f, 0.50f, 0.28f)
                    "down" -> floatArrayOf(0.50f, 0.28f, 0.50f, 0.78f)
                    "left" -> floatArrayOf(0.82f, 0.50f, 0.18f, 0.50f)
                    "right" -> floatArrayOf(0.18f, 0.50f, 0.82f, 0.50f)
                    else -> null
                }
                val swiped = points?.let { DeviceAccessibilityService.remoteSwipe(it[0], it[1], it[2], it[3]) } ?: false
                result.put("status", if (swiped) "success" else "error")
                result.put("action", if (swiped) "swipe_dispatched" else "invalid_direction_or_accessibility_off")
            }

            "go_back" -> {
                val ok = DeviceAccessibilityService.goBack()
                result.put("status", if (ok) "success" else "error")
                result.put("action", if (ok) "done" else "accessibility_not_enabled")
            }

            "go_home" -> {
                val ok = DeviceAccessibilityService.goHome()
                result.put("status", if (ok) "success" else "error")
                result.put("action", if (ok) "done" else "accessibility_not_enabled")
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
                            val service = NovaAccessibilityService.instance
                            val opened = if (service == null) "whatsapp_accessibility_required" else CommIntents.openWhatsAppComposer(context, number, message)
                            val sent = if (opened == "whatsapp_chat_opened") service!!.sendWhatsAppMessage(args.optString("contact_name", recipient), number, message) else opened
                            result.put("status", if (sent == "whatsapp_sent") "success" else "error")
                            result.put("action", sent)
                            result.put("recipient", number)
                            result.put("message", if (sent == "whatsapp_sent") "WhatsApp message sent." else "Message not sent: $sent")
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
