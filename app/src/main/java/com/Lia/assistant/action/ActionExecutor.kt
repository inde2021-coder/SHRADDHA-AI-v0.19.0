package com.Lia.assistant.action

import android.content.Context
import com.Lia.assistant.agent.NovaAccessibilityService
import com.Lia.assistant.license.AccessKeyManager
import com.Lia.assistant.forge.ForgeController
import com.Lia.assistant.data.VoiceGatewaySettings
import `in`.shraddha.ai.link.DeviceLinkManager
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService as DeviceAccessibilityService
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy
import `in`.shraddha.ai.operator.learning.AppProfileStore
import `in`.shraddha.ai.operator.learning.WorkflowCheckpointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
            "build_website" -> {
                val prompt = args.optString("prompt", "").trim()
                if (prompt.isBlank() || prompt.length > 8000) {
                    result.put("status", "error")
                    result.put("message", "Website prompt must contain 1-8000 characters.")
                } else {
                    val start = withContext(Dispatchers.Main.immediate) { ForgeController.start(context, prompt) }
                    if (start is com.Lia.assistant.forge.ForgeStartResult.Started) {
                        result.put("status", "success")
                        result.put("action", "forge_started")
                    } else {
                        result.put("status", "error")
                        result.put("action", when (start) {
                            is com.Lia.assistant.forge.ForgeStartResult.MissingKey -> "provider_key_missing"
                            is com.Lia.assistant.forge.ForgeStartResult.Offline -> "offline"
                            is com.Lia.assistant.forge.ForgeStartResult.Busy -> "forge_busy"
                            is com.Lia.assistant.forge.ForgeStartResult.NothingToEdit -> "nothing_to_edit"
                            else -> "forge_start_failed"
                        })
                    }
                    result.put("message", start.message)
                }
            }
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
                        result.put("status", when (callStatus) { "call_started" -> "success"; "dialer_opened_no_permission" -> "needs_user_action"; else -> "error" })
                        result.put("action", callStatus)
                        result.put("message", when (callStatus) {
                            "call_started" -> "Calling ${lookup.displayName}."
                            "dialer_opened_no_permission" -> "फोन dialer खुल गया है। कॉल करने के लिए नंबर और Call बटन जाँचकर स्वयं टैप करें।"
                            else -> "Could not start the call."
                        })
                        result.put("contact", lookup.displayName)
                        result.put("number", lookup.phoneNumber)
                    }
                    is ContactLookupResult.Ambiguous -> {
                        result.put("status", "needs_clarification")
                        result.put("action", "ambiguous_contact")
                        result.put("candidates", JSONArray().apply { lookup.candidates.forEach { put(it.first) } })
                        result.put("message", "एक से अधिक contacts मिले। पूरा नाम बताइए; अभी कोई call नहीं किया गया।")
                    }
                    is ContactLookupResult.NoPermission -> {
                        result.put("status", "error")
                        result.put("action", "no_contacts_permission")
                        result.put("message", "Shraddha AI → Permissions में Enable Contacts + Calling या Enable Contacts + SMS चालू करें; चाहें तो पूरा phone number बोलें।")
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
                        result.put("message", "कई contacts मिले। सही व्यक्ति का पूरा नाम बताइए; कोई संदेश नहीं भेजा गया।")
                        return result
                    }
                    ContactLookupResult.NoPermission -> {
                        result.put("status", "error")
                        result.put("action", "contacts_permission_required")
                        result.put("message", "नाम से contact खोजने के लिए Shraddha AI → Permissions → Enable Contacts + Calling/SMS चालू करें; या पूरा phone number दें।")
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

                val userConfirmed = args.optString("user_confirmed", "").equals("yes", true)
                val confirmedRecipient = args.optString("confirmed_recipient", "").trim()
                val confirmedMessage = args.optString("confirmed_message", "")
                // Bind approval to the exact resolved recipient and exact message body.
                // A generic yes must never authorize a changed recipient or edited text.
                if (!MessageApprovalPolicy.isApproved(
                        actualRecipient = phoneNumber,
                        actualMessage = messageText,
                        userConfirmed = userConfirmed,
                        approvedRecipient = confirmedRecipient,
                        approvedMessage = confirmedMessage
                    )) {
                    result.put("status", "confirmation_required")
                    result.put("action", "message_confirmation_required")
                    result.put("recipient", phoneNumber)
                    result.put("message_preview", messageText.take(1000))
                    result.put("message", "भेजने से पहले उपयोगकर्ता को यही प्राप्तकर्ता और यही पूरा संदेश दिखाएँ। स्पष्ट हाँ मिलने के बाद user_confirmed=yes, confirmed_recipient में यही नंबर और confirmed_message में यही अक्षरशः संदेश दें। अभी कोई SMS/WhatsApp नहीं भेजा गया।")
                    return result
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
                    "sms_permission_required" -> "Shraddha AI → Permissions → Enable Contacts + SMS दबाएँ, फिर SMS command दोहराएँ।"
                    "whatsapp_accessibility_required" -> "Enable Shraddha AI Accessibility permission to send WhatsApp messages by voice."
                    "whatsapp_recipient_not_verified" -> "WhatsApp recipient could not be verified on screen; message was not sent."
                    else -> "Message was not sent: $sendStatus"
                })
            }

            "read_screen" -> {
                val snapshot = DeviceAccessibilityService.captureVisibleScreen()
                val ocrEnabled = context.getSharedPreferences("shraddha_privacy", Context.MODE_PRIVATE)
                    .getBoolean("on_device_screen_ocr", false)
                val needsOcr = ocrEnabled && (snapshot == null || snapshot.elements.size < 4)
                val ocrText = if (needsOcr) DeviceAccessibilityService.captureOcrText() else null
                if (snapshot == null && ocrText.isNullOrBlank()) {
                    result.put("status", "error")
                    result.put("action", "screen_unavailable")
                    result.put("message", "Enable Shraddha AI — Device Control. Optional OCR also requires Settings → On-device OCR Screen Reading and Android 11+. Secure/protected screens cannot be captured.")
                } else {
                    result.put("status", "success")
                    result.put("action", if (ocrText.isNullOrBlank()) "screen_read" else "screen_read_with_local_ocr")
                    snapshot?.let {
                        result.put("package", it.packageName)
                        val array = JSONArray()
                        it.elements.forEach { element ->
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
                    if (!ocrText.isNullOrBlank()) result.put("ocr_text", ocrText)
                    result.put("ocr_used", !ocrText.isNullOrBlank())
                    result.put("ocr_local_only", true)
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

            "tap_ocr_text" -> {
                val text = args.optString("text", "").trim()
                val enabled = context.getSharedPreferences("shraddha_privacy", Context.MODE_PRIVATE)
                    .getBoolean("on_device_screen_ocr", false)
                val decision = OperatorSafetyPolicy.assess("tap", target = text)
                val confirmed = args.optString("user_confirmed", "").equals("yes", true)
                if (!enabled) {
                    result.put("status", "blocked")
                    result.put("action", "ocr_opt_in_required")
                    result.put("message", "पहले Settings में On-device OCR Screen Reading को ON करें।")
                } else if (decision.risk == OperatorSafetyPolicy.Risk.BLOCK) {
                    result.put("status", "blocked")
                    result.put("action", "sensitive_action_blocked")
                    result.put("message", decision.reason)
                } else if (decision.risk == OperatorSafetyPolicy.Risk.CONFIRM && !confirmed) {
                    result.put("status", "confirmation_required")
                    result.put("action", "waiting_for_user_confirmation")
                    result.put("message", "इस OCR target पर tap करने से पहले उपयोगकर्ता की स्पष्ट पुष्टि लें।")
                } else {
                    val targets = DeviceAccessibilityService.captureOcrTargets().orEmpty()
                    val exact = targets.filter { !it.sensitive && it.text.equals(text, true) }
                    val matches = if (exact.isNotEmpty()) exact else targets.filter { !it.sensitive && it.text.contains(text, true) }
                    val tapped = if (text.isNotBlank() && matches.size == 1) {
                        DeviceAccessibilityService.remoteTap(matches.single().centerX, matches.single().centerY)
                    } else false
                    result.put("status", if (tapped) "success" else "error")
                    result.put("action", if (tapped) "ocr_target_tapped" else "ocr_target_missing_or_ambiguous")
                    result.put("message", if (tapped) "Unique OCR target tapped; inspect the screen again to verify the result." else "OCR target was not unique or could not be tapped. Ask the user to clarify.")
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

            "list_app_workflows" -> {
                val profiles = AppProfileStore(context).all()
                val array = JSONArray()
                profiles.forEach { profile ->
                    val workflows = JSONArray()
                    val resumable = JSONArray()
                    val checkpointStore = WorkflowCheckpointStore(context.applicationContext)
                    profile.workflows.forEach { (workflowName, steps) ->
                        workflows.put(workflowName)
                        checkpointStore.get(profile.packageName, workflowName)?.let { checkpoint ->
                            val valid = checkpoint.workflowHash == checkpointStore.fingerprint(steps) &&
                                checkpoint.totalSteps == steps.size &&
                                checkpoint.state in setOf("READY", "PAUSED", "WAITING_CONFIRMATION")
                            if (valid) resumable.put(JSONObject()
                                .put("workflow_name", workflowName)
                                .put("state", checkpoint.state)
                                .put("next_step", checkpoint.nextStep + 1)
                                .put("total_steps", checkpoint.totalSteps))
                        }
                    }
                    array.put(JSONObject()
                        .put("app_name", profile.displayName)
                        .put("package_name", profile.packageName)
                        .put("workflows", workflows)
                        .put("resumable_checkpoints", resumable))
                }
                result.put("status", "success")
                result.put("profiles", array)
                result.put("message", if (profiles.isEmpty()) "No app workflows have been trained yet. Open Control Any App → App Training to teach one." else "Saved app workflows are listed.")
            }

            "run_app_workflow" -> {
                val appQuery = args.optString("app_name", "").trim()
                val workflowQuery = args.optString("workflow_name", "").trim()
                if (appQuery.isBlank() || workflowQuery.isBlank()) {
                    result.put("status", "error")
                    result.put("action", "app_and_workflow_required")
                    result.put("message", "Provide the trained app name and exact saved workflow name.")
                } else {
                    val profiles = AppProfileStore(context).all()
                    val appMatches = profiles.filter {
                        it.displayName.equals(appQuery, true) || it.packageName.equals(appQuery, true)
                    }.ifEmpty {
                        profiles.filter { it.displayName.contains(appQuery, true) }
                    }
                    if (appMatches.size != 1) {
                        result.put("status", "error")
                        result.put("action", if (appMatches.isEmpty()) "app_profile_not_found" else "app_profile_ambiguous")
                        result.put("message", if (appMatches.isEmpty()) "No trained profile for this app. Open Control Any App → App Training first." else "More than one trained app matches. Ask the user to specify the exact app name.")
                    } else {
                        val profile = appMatches.single()
                        val workflowMatches = profile.workflows.keys.filter { it.equals(workflowQuery, true) }
                        if (workflowMatches.size != 1) {
                            result.put("status", "error")
                            result.put("action", "workflow_not_found_or_ambiguous")
                            result.put("message", "No unique saved workflow matches that name. Ask the user to choose one from the saved workflow list.")
                        } else {
                            val workflowName = workflowMatches.single()
                            val values = linkedMapOf<String, String>()
                            val rawValues = runCatching { JSONObject(args.optString("values_json", "{}")) }.getOrNull()
                            if (rawValues != null) {
                                val keys = rawValues.keys()
                                var count = 0
                                while (keys.hasNext() && count < 30) {
                                    val key = keys.next().take(80)
                                    val value = rawValues.optString(key, "").take(2000)
                                    if (key.isNotBlank() && value.isNotBlank()) values[key] = value
                                    count++
                                }
                            }
                            val steps = profile.workflows[workflowName].orEmpty()
                            val blocked = steps.firstOrNull { step ->
                                val value = values[step.inputTemplate] ?: step.inputTemplate
                                OperatorSafetyPolicy.assess(step.action, step.targetLabel, value).risk == OperatorSafetyPolicy.Risk.BLOCK
                            }
                            val confirmed = args.optString("user_confirmed", "").equals("yes", true)
                            if (blocked != null) {
                                result.put("status", "blocked")
                                result.put("action", "sensitive_workflow_blocked")
                                result.put("message", "This workflow targets a protected password/OTP/PIN or sensitive field and cannot be automated.")
                            } else {
                                val runResult = withContext(Dispatchers.IO) {
                                    var detail = "Workflow did not complete."
                                    val ok = `in`.shraddha.ai.future.MainBrain.runLearnedWorkflow(
                                        context.applicationContext, profile, workflowName, values,
                                        confirmed, args.optBoolean("resume", false),
                                        withinMainBrainTask = true
                                    ) { event -> detail = "${event.state}: ${event.message}" }
                                    ok to detail
                                }
                                val waitingForApproval = runResult.second.startsWith("WAITING_CONFIRMATION:")
                                result.put("status", when { runResult.first -> "success"; waitingForApproval -> "confirmation_required"; else -> "error" })
                                result.put("action", when { runResult.first -> "workflow_completed"; waitingForApproval -> "waiting_for_user_confirmation"; else -> "workflow_stopped" })
                                result.put("message", runResult.second)
                                result.put("app", profile.displayName)
                                result.put("workflow", workflowName)
                            }
                        }
                    }
                }
            }

            "execute_social_media_task" -> {
                val platform = args.optString("platform", "").trim().lowercase()
                val action = args.optString("action", "TEXT_POST").trim().uppercase()
                val mode = args.optString("mode", "DRAFT").trim().uppercase()
                val text = args.optString("text").ifBlank { args.optString("caption") }.trim()
                val validationError = SocialPublishApprovalPolicy.validate(platform, action, text)
                val maxLength = SocialPublishApprovalPolicy.maxCharacters(platform)
                when {
                    validationError != null -> {
                        result.put("status", "error")
                        result.put("action", if (maxLength == 0) "unsupported_social_platform_or_format" else "invalid_social_post")
                        result.put("platform", platform)
                        result.put("character_limit", maxLength)
                        result.put("message", validationError)
                    }
                    mode == "DRAFT" -> {
                        result.put("status", "draft")
                        result.put("action", "social_post_draft_ready")
                        result.put("platform", platform)
                        result.put("text_preview", text)
                        result.put("message", "Draft prepared only. Nothing was sent. Set mode=PUBLISH to request the exact-preview confirmation.")
                    }
                    mode != "PUBLISH" -> {
                        result.put("status", "error")
                        result.put("action", "invalid_social_publish_mode")
                        result.put("message", "mode must be DRAFT or PUBLISH.")
                    }
                    !SocialPublishApprovalPolicy.isApproved(
                        platform = platform,
                        exactText = text,
                        userConfirmed = args.optString("user_confirmed", "").equals("yes", true),
                        confirmedPlatform = args.optString("confirmed_platform", ""),
                        confirmedText = args.optString("confirmed_text", "")
                    ) -> {
                        result.put("status", "confirmation_required")
                        result.put("action", "social_publish_confirmation_required")
                        result.put("platform", platform)
                        result.put("text_preview", text)
                        result.put("message", "इस exact post को ${platform} पर प्रकाशित करने से पहले उपयोगकर्ता को पूरा preview दिखाएँ। केवल स्पष्ट अनुमति मिलने पर user_confirmed=yes, confirmed_platform और confirmed_text को इसी preview के अक्षरशः बराबर देकर दोबारा call करें। अभी कुछ प्रकाशित नहीं हुआ है।")
                    }
                    else -> {
                        val config = VoiceGatewaySettings.get(context)
                        if (config.baseUrl.isBlank() || config.gatewayToken.isBlank()) {
                            result.put("status", "error")
                            result.put("action", "social_backend_not_configured")
                            result.put("message", "पहले Settings में HTTPS backend URL और gateway token configure करें। Social platform credentials केवल backend environment में रखें।")
                        } else {
                            val response = publishSocialPost(config.baseUrl, config.gatewayToken, platform, text)
                            response.onSuccess { raw ->
                                val body = runCatching { JSONObject(raw) }.getOrNull()
                                if (body != null && body.optBoolean("ok") && body.optString("platform") == platform) {
                                    val postId = body.optString("postId").ifBlank { body.optString("messageId") }
                                    result.put("status", "success")
                                    result.put("action", "social_post_published")
                                    result.put("platform", platform)
                                    result.put("post_id", postId)
                                    result.put("message", "Post accepted by ${platform}. Reference: ${postId.ifBlank { "accepted" }}. Verify it on the platform.")
                                    runCatching { `in`.shraddha.ai.future.LocalAuditTrail(context).append("social_publish", platform, "User-confirmed text post") }
                                } else {
                                    result.put("status", "error")
                                    result.put("action", "social_publish_rejected")
                                    result.put("message", body?.optString("error")?.ifBlank { "Platform did not confirm publication." } ?: "Backend returned an unreadable publication response.")
                                }
                            }.onFailure {
                                result.put("status", "error")
                                result.put("action", "social_publish_failed")
                                result.put("message", "Publication failed: ${it.message ?: "backend or platform error"}. Check the platform before retrying to avoid duplicate posts.")
                            }
                        }
                    }
                }
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
                            is ContactLookupResult.Ambiguous -> {
                                result.put("status", "needs_clarification")
                                result.put("action", "ambiguous_contact")
                                result.put("candidates", JSONArray().apply { lookup.candidates.forEach { put(it.first) } })
                                result.put("message", "कई contacts मिले। सही प्राप्तकर्ता का पूरा नाम बताइए; अभी संदेश नहीं भेजा गया।")
                                return result
                            }
                            ContactLookupResult.NoPermission -> if (ContactResolver.isDirectPhoneNumber(recipient)) recipient else ""
                            ContactLookupResult.NotFound -> if (ContactResolver.isDirectPhoneNumber(recipient)) recipient else ""
                        }
                        if (number.isBlank()) {
                            result.put("status", "error")
                            result.put("action", "recipient_not_found")
                            result.put("message", "Provide a full phone number or grant Contacts permission.")
                        } else {
                            val approved = MessageApprovalPolicy.isApproved(
                                actualRecipient = number,
                                actualMessage = message,
                                userConfirmed = args.optString("user_confirmed", "").equals("yes", true),
                                approvedRecipient = args.optString("confirmed_recipient", "").trim(),
                                approvedMessage = args.optString("confirmed_message", "")
                            )
                            if (!approved) {
                                result.put("status", "confirmation_required")
                                result.put("action", "message_confirmation_required")
                                result.put("recipient", number)
                                result.put("message_preview", message.take(1000))
                                result.put("message", "उपयोगकर्ता को यही नंबर और यही पूरा संदेश दिखाकर स्पष्ट अनुमति लें। अभी WhatsApp नहीं भेजा गया।")
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

    private suspend fun publishSocialPost(baseUrl: String, gatewayToken: String, platform: String, exactText: String): Result<String> =
        suspendCancellableCoroutine { continuation ->
            DeviceLinkManager.publishSocialPost(
                baseUrl, gatewayToken, platform, exactText,
                userConfirmed = true, confirmedPlatform = platform, confirmedText = exactText,
                onCall = { request -> continuation.invokeOnCancellation { request.cancel() } }
            ) { response ->
                if (continuation.isActive) continuation.resume(response)
            }
        }

}
