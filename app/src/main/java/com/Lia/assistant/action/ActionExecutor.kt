package com.Lia.assistant.action

import android.content.Context
import android.content.Intent
import `in`.shraddha.ai.phone.PhoneAgentActivity
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
                        result.put("status", if (callStatus == "calling") "success" else "needs_user_action")
                        result.put("action", callStatus)
                        result.put("contact", lookup.displayName)
                        result.put("number", lookup.phoneNumber)
                        if (callStatus != "calling") {
                            result.put("message", "The phone dialer was opened, but the call was not placed automatically.")
                        }
                    }
                    is ContactLookupResult.NoPermission -> {
                        try {
                            val permissionIntent = Intent(context, PhoneAgentActivity::class.java).apply {
                                putExtra(PhoneAgentActivity.EXTRA_PENDING_CONTACT_QUERY, query)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(permissionIntent)
                            result.put("status", "needs_user_action")
                            result.put("action", "contacts_permission_requested")
                            result.put("message", "The phone agent opened the Android Contacts permission prompt. Wait for the user to grant it, then retry the call.")
                        } catch (error: Exception) {
                            result.put("status", "error")
                            result.put("action", "contacts_permission_unavailable")
                            result.put("message", error.message ?: "Open Phone Agent and grant Contacts permission.")
                        }
                    }
                    is ContactLookupResult.Ambiguous -> {
                        result.put("status", "needs_clarification")
                        result.put("action", "ambiguous_contact")
                        result.put("candidates", JSONArray().apply {
                            lookup.candidates.forEach { (name, _) -> put(name) }
                        })
                        result.put("message", "Multiple contacts match. Ask the user to say the full contact name; do not place a call yet.")
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
                    is ContactLookupResult.Ambiguous -> {
                        result.put("status", "needs_clarification")
                        result.put("action", "ambiguous_contact")
                        result.put("candidates", JSONArray().apply { lookup.candidates.forEach { put(it.first) } })
                        result.put("message", "Multiple contacts match. Ask the user to identify the exact recipient before opening a message.")
                        return result
                    }
                    is ContactLookupResult.NoPermission -> {
                        result.put("status", "needs_user_action")
                        result.put("action", "contacts_permission_required")
                        result.put("message", "Grant Contacts permission before selecting a saved contact.")
                        return result
                    }
                    is ContactLookupResult.NotFound -> {
                        result.put("status", "error")
                        result.put("action", "contact_not_found")
                        result.put("message", "Recipient was not found. Do not treat a name as a phone number.")
                        return result
                    }
                }

                val isWhatsApp = targetApp == "whatsapp"
                val composerStatus = if (isWhatsApp) {
                    CommIntents.openWhatsAppComposer(context, phoneNumber, messageText)
                } else {
                    CommIntents.openSmsComposer(context, phoneNumber, messageText)
                }

                // Never press Send automatically. The user must review the recipient and message.
                result.put("status", if (composerStatus == "composer_opened") "needs_user_action" else "error")
                result.put("action", composerStatus)
                result.put("recipient", phoneNumber)
                result.put("message", if (composerStatus == "composer_opened") "Message composer opened. Review the recipient and message, then tap Send yourself." else "Message composer could not be opened.")
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
                result.put("status", "not_implemented")
                result.put("action", "social_task_unavailable")
                result.put("message", "Social media task execution is not connected yet. No task was queued and no post was published.")
            }

            "social_media_task_control" -> {
                result.put("status", "not_implemented")
                result.put("action", "social_task_control_unavailable")
                result.put("message", "Social media task controls are not connected; no command was executed.")
            }

            "execute_whatsapp_task" -> {
                result.put("status", "not_implemented")
                result.put("action", "whatsapp_task_unavailable")
                result.put("message", "WhatsApp workflow automation is not connected; no task was queued.")
            }

            "whatsapp_task_control" -> {
                result.put("status", "not_implemented")
                result.put("action", "whatsapp_task_control_unavailable")
                result.put("message", "WhatsApp workflow controls are not connected; no command was executed.")
            }

            else -> {
                result.put("error", "Unknown tool: $toolName")
            }
        }

        return result
    }
}
