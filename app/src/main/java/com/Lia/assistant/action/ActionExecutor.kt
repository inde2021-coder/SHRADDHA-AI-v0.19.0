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

    suspend fun execute(context: Context, toolName: String, args: JSONObject, withinMainBrainTask: Boolean = false): JSONObject {
        val result = JSONObject()

        // Licensing check
        if (!AccessKeyManager.isAccessGranted()) {
            result.put("status", "error")
            result.put("action", "license_required")
            result.put("message", "A valid access key is required to use this direct capability. Please enter your access key in Profile Settings.")
            return result
        }


        when (toolName) {


            "inspect_akkps_runner" -> {
                `in`.shraddha.ai.operator.website.AkkpsPageInventory.clear(context)
                val launch = android.content.Intent(context, `in`.shraddha.ai.operator.website.AkkpsRunnerActivity::class.java)
                    .putExtra(`in`.shraddha.ai.operator.website.AkkpsRunnerActivity.EXTRA_RUNNER_INSPECT, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(launch)
                result.put("status", "needs_user_action").put("action", "akkps_runner_inspection_started")
                    .put("message", "AKKPS Runner खुल गया है। Login आप स्वयं करें; authenticated Runner page load होने पर safe module/form inventory अपने आप capture होगी।")
            }

            "get_akkps_runner_inventory" -> {
                val inventory = `in`.shraddha.ai.operator.website.AkkpsPageInventory.last(context)
                    ?: return result.put("status", "needs_user_action").put("message", "अभी AKKPS Runner inventory उपलब्ध नहीं है। पहले inspect_akkps_runner चलाएँ और वेबसाइट में स्वयं Login करें।")
                val capturedAt = inventory.optLong("capturedAt", 0L)
                if (capturedAt <= 0L || System.currentTimeMillis() - capturedAt > 30 * 60 * 1000L) {
                    return result.put("status", "needs_user_action").put("message", "AKKPS page inventory पुरानी है। वर्तमान login session में inspect_akkps_runner दोबारा चलाएँ।")
                }
                result.put("status", "success")
                    .put("inventory", inventory)
                    .put("summary", `in`.shraddha.ai.operator.website.AkkpsPageInventory.summary(inventory))
                    .put("message", "यह केवल safe page structure है; farmer rows, form values, cookies और credentials इसमें शामिल नहीं हैं।")
            }

            "open_akkps_runner" -> {
                val command = args.optString("command").trim()
                if (command.length > 3000) return result.put("status", "error").put("message", "AKKPS command अधिकतम 3000 characters की हो सकती है।")
                if (Regex("(?i)\\b(password|passcode|otp|one[- ]time (?:password|code|pin)|pin|cvv|cvc|captcha|security code|verification code|recovery phrase|secret key)\\b|पासवर्ड|ओटीपी|पिन|गुप्त कुंजी|सत्यापन कोड").containsMatchIn(command)) {
                    return result.put("status", "error").put("message", "Password, OTP, PIN या CAPTCHA सामग्री Runner command में नहीं भेजी जा सकती। वेबसाइट पर स्वयं दर्ज करें।")
                }
                val launch = android.content.Intent(context, `in`.shraddha.ai.operator.website.AkkpsRunnerActivity::class.java)
                    .putExtra(`in`.shraddha.ai.operator.website.AkkpsRunnerActivity.EXTRA_RUNNER_COMMAND, command)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(launch)
                result.put("status", "needs_user_action").put("action", "akkps_runner_opened")
                    .put("command_loaded", command.isNotBlank())
                    .put("message", if (command.isBlank())
                        "AKKPS Runner खुल गया है। Login आप स्वयं करें; उसके बाद Inspect से पेज और उपलब्ध modules पहचानें। Password/OTP Shraddha AI को नहीं दिए जाते।"
                    else "AKKPS Runner खुल गया है। आपकी command authenticated Runner पेज लोड होने पर चलायी जाएगी; fields review होंगे और Submit अलग पुष्टि माँगेगा। Password/OTP Shraddha AI को नहीं दिए जाते।")
            }

            "list_paired_devices" -> {
                val devices = `in`.shraddha.ai.link.DeviceLinkManager.pairedDevices()
                val array = org.json.JSONArray()
                devices.forEach { peer ->
                    array.put(JSONObject().put("device_id", peer.id).put("name", peer.name)
                        .put("online", peer.online).put("platform", peer.platform))
                }
                result.put("status", "success").put("devices", array)
                    .put("message", if (devices.isEmpty()) "कोई paired device अभी relay पर उपलब्ध नहीं है। Device Universe में pairing/connection जाँचें।"
                        else "Paired device list relay से पढ़ी गई है। Offline device पर command नहीं भेजी जा सकती।")
            }
            "control_paired_pc" -> {
                val target = args.optString("target_device_id").trim()
                val kind = args.optString("kind").trim().lowercase()
                if (!target.matches(Regex("[A-Za-z0-9_-]{8,128}"))) return result.put("status", "error").put("message", "Exact paired PC device ID आवश्यक है। Device Universe में ID देखें; कोई target अनुमान से नहीं चुना जाएगा।")
                val peer = `in`.shraddha.ai.link.DeviceLinkManager.pairedDevices().firstOrNull { it.id == target }
                    ?: return result.put("status", "error").put("message", "यह device इस समय paired-device list में नहीं है। पहले list_paired_devices चलाएँ।")
                if (!peer.online) return result.put("status", "error").put("message", "${peer.name} अभी offline है; कोई PC command नहीं भेजी गई।")
                val action = JSONObject().put("kind", kind)
                when (kind) {
                    "hardware_status", "back", "home", "screenshot", "akkps_open", "akkps_runner", "akkps_inspect" -> Unit
                    "akkps_module" -> {
                        val module = args.optString("module").trim().lowercase()
                        if (module !in setOf("farmers", "plots", "awd reports", "profile", "data sync")) return result.put("status", "error").put("message", "Unknown AKKPS Runner module.")
                        action.put("module", module)
                    }
                    "set_volume", "set_brightness" -> {
                        val percent = args.optInt("percent", -1)
                        if (percent !in 0..100) return result.put("status", "error").put("message", "PC percent 0 से 100 के बीच होना चाहिए।")
                        action.put("percent", percent)
                    }
                    "power" -> {
                        val operation = args.optString("operation").lowercase()
                        if (operation !in setOf("shutdown", "restart", "sleep")) return result.put("status", "error").put("message", "Power operation shutdown, restart या sleep होना चाहिए।")
                        if (!args.optString("user_confirmed").equals("yes", true)) return result.put("status", "confirmation_required").put("message", "PC $operation से पहले उपयोगकर्ता से स्पष्ट हाँ लें; अभी कोई command नहीं भेजी गई।")
                        action.put("operation", operation)
                    }
                    "open_app" -> {
                        val name = args.optString("name").trim()
                        if (name.isBlank() || name.length > 100) return result.put("status", "error").put("message", "PC app का नाम 1-100 characters होना चाहिए।")
                        action.put("name", name)
                    }
                    "scroll" -> {
                        val amount = args.optInt("amount", 0)
                        if (amount !in -10..10 || amount == 0) return result.put("status", "error").put("message", "Scroll amount -10 से 10 के बीच non-zero होना चाहिए।")
                        action.put("amount", amount)
                    }
                    "web_search" -> {
                        val query = args.optString("query").trim()
                        if (query.length !in 2..160) return result.put("status", "error").put("message", "PC web search query 2-160 characters की होनी चाहिए।")
                        action.put("query", query)
                    }
                    else -> return result.put("status", "error").put("message", "Unsupported PC control kind.")
                }
                val launch = android.content.Intent(context, `in`.shraddha.ai.MainActivity::class.java)
                    .putExtra("initial_page", "devices")
                    .putExtra("voice_pc_action_json", action.toString())
                    .putExtra("voice_pc_target_id", target)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(launch)
                result.put("status", "needs_user_action").put("action", "pc_control_approval_requested")
                    .put("target_device_id", target).put("command_kind", kind)
                    .put("message", "Device Control खोला गया है। पहले paired Windows PC से fresh permission ली जाएगी; फिर Windows companion की per-action confirmation होगी। अभी execution confirmed नहीं है।")
            }
            "control_phone" -> {
                val action = args.optString("action").trim().lowercase()
                val percent = args.optInt("percent", -1)
                val hardware = `in`.shraddha.ai.mobile.MobileHardwareController
                when (action) {
                    "status" -> {
                        val data = runCatching { hardware.status(context.applicationContext) }.getOrElse {
                            return result.put("status", "error").put("message", it.localizedMessage ?: "Phone status unavailable.")
                        }
                        result.put("status", "success").put("action", "phone_status")
                            .put("device", JSONObject(data))
                            .put("message", "यह इस Android फोन की उपलब्ध hardware जानकारी है।")
                    }
                    "set_volume", "set_brightness" -> {
                        if (percent !in 0..100) return result.put("status", "error").put("message", "percent 0 से 100 के बीच integer होना चाहिए।")
                        if (action == "set_brightness" && !android.provider.Settings.System.canWrite(context)) {
                            val message = hardware.setBrightness(context.applicationContext, percent)
                            return result.put("status", "needs_user_action").put("action", "modify_system_settings_required")
                                .put("percent", percent).put("message", message)
                        }
                        val message = runCatching {
                            if (action == "set_volume") hardware.setMediaVolume(context.applicationContext, percent)
                            else hardware.setBrightness(context.applicationContext, percent)
                        }.getOrElse { return result.put("status", "error").put("message", it.localizedMessage ?: "Phone control failed.") }
                        result.put("status", "success").put("action", action).put("percent", percent).put("message", message)
                    }
                    "flashlight_on", "flashlight_off" -> {
                        val enabled = action == "flashlight_on"
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            val launch = android.content.Intent(context, `in`.shraddha.ai.mobile.MobileCommandCenterActivity::class.java)
                                .putExtra("mobile_control_command", if (enabled) "flashlight on" else "flashlight off")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launch)
                            result.put("status", "needs_user_action").put("action", "camera_permission_required")
                                .put("message", "Mobile Command Center खोला गया है। Camera permission स्वीकार करने के बाद flashlight command फिर चलेगी।")
                        } else {
                            val message = runCatching { hardware.setTorch(context.applicationContext, enabled) }
                                .getOrElse { return result.put("status", "error").put("message", it.localizedMessage ?: "Flashlight control failed.") }
                            result.put("status", "success").put("action", action).put("message", message)
                        }
                    }
                    "volume_up", "volume_down" -> {
                        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            if (action == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                            android.media.AudioManager.FLAG_SHOW_UI)
                        result.put("status", "success").put("action", action).put("message", if (action == "volume_up") "Phone volume बढ़ाया।" else "Phone volume कम किया।")
                    }
                    "media_play_pause", "media_next", "media_previous" -> {
                        val key = when (action) {
                            "media_next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                            "media_previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                            else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        }
                        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                        result.put("status", "success").put("action", action).put("message", "Active Android media session को command भेजा गया।")
                    }
                    "open_settings" -> {
                        val setting = args.optString("setting").trim().lowercase()
                        val allowed = setOf("wifi", "bluetooth", "internet", "display", "sound", "battery", "location", "nfc", "accessibility", "notifications", "apps", "date", "language", "security", "dnd", "rotation", "storage")
                        if (setting !in allowed) return result.put("status", "error").put("message", "Unsupported Android settings key.")
                        context.startActivity(hardware.settingsIntent(setting).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.put("status", "needs_user_action").put("action", "android_settings_opened")
                            .put("setting", setting).put("message", "Android का official $setting settings panel खोला गया है; protected changes वहीं confirm करें।")
                    }
                    else -> result.put("status", "error").put("message", "Unsupported control_phone action.")
                }
            }

            "download_file" -> {
                val download = `in`.shraddha.ai.future.DownloadUrlPolicy.validate(args.optString("url"), args.optString("file_name"))
                if (download == null) {
                    result.put("status", "error").put("action", "download_url_rejected")
                    result.put("message", "सिर्फ सार्वजनिक HTTPS URL और मान्य file name से download किया जा सकता है।")
                } else {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    val request = android.app.DownloadManager.Request(android.net.Uri.parse(download.httpsUrl))
                        .setTitle(download.fileName).setDescription("Shraddha AI file download")
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, download.fileName)
                    val downloadId = manager.enqueue(request)
                    result.put("status", "queued").put("action", "download_queued").put("download_id", downloadId)
                    result.put("file_name", download.fileName).put("message", "Download Android Download Manager में queue हो गया है। पूरा होने की सूचना Android देगा; अभी download complete होने का दावा नहीं है।")
                }
            }
            "daily_briefing" -> {
                val action = args.optString("action", "ENABLE").uppercase()
                if (action == "DISABLE" || action == "STOP") {
                    val stopped = `in`.shraddha.ai.future.DailyBriefingScheduler.cancel(context)
                    result.put("status", if (stopped.isSuccess) "success" else "error")
                    result.put("action", "daily_briefing_disabled")
                    result.put("message", if (stopped.isSuccess) "Daily Briefing बंद कर दी गई है।" else (stopped.exceptionOrNull()?.message ?: "Briefing बंद नहीं हुई।"))
                } else if (action == "ENABLE" || action == "START" || action == "UPDATE") {
                    val time = args.optString("time", "08:00").trim()
                    val match = Regex("^(?:([01]?[0-9]|2[0-3])):([0-5][0-9])$").matchEntire(time)
                    if (match == null) {
                        result.put("status", "error"); result.put("message", "समय HH:MM (24-hour) format में दें, जैसे 08:00।")
                    } else {
                        val hour = match.groupValues[1].toInt()
                        val minute = match.groupValues[2].toInt()
                        val scheduled = `in`.shraddha.ai.future.DailyBriefingScheduler.schedule(context, args.optString("topics"), hour, minute)
                        result.put("status", if (scheduled.isSuccess) "success" else "error")
                        result.put("action", if (scheduled.isSuccess) "daily_briefing_scheduled" else "daily_briefing_schedule_failed")
                        scheduled.getOrNull()?.let {
                            result.put("topics", it.topics).put("local_time", "%02d:%02d".format(it.hour, it.minute))
                                .put("first_run_in_minutes", it.initialDelayMillis / 60000L)
                        }
                        result.put("message", scheduled.exceptionOrNull()?.message ?: "Daily Briefing schedule हो गई। अगली briefing आपके चुने हुए स्थानीय समय पर, फिर लगभग हर 24 घंटे में आएगी।")
                    }
                } else {
                    result.put("status", "error"); result.put("message", "action ENABLE या DISABLE होना चाहिए।")
                }
            }
            "manage_files" -> {
                val operation = args.optString("operation", "manage").uppercase()
                val allowed = setOf("MANAGE", "FIND", "OPEN", "COPY", "MOVE", "RENAME", "DELETE", "SHARE", "IMPORT", "EXPORT")
                if (operation !in allowed) {
                    result.put("status", "error")
                    result.put("message", "Unsupported file operation.")
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        val intent = android.content.Intent(context, `in`.shraddha.ai.operator.files.FileAccessActivity::class.java)
                            .putExtra("requested_operation", operation)
                        if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    result.put("status", "needs_user_action")
                    result.put("action", "file_manager_opened")
                    result.put("operation", operation.lowercase())
                    result.put("message", "फ़ाइल प्रबंधक खुल गया है। फ़ाइल चुनें; Delete/Move/Rename से पहले पुष्टि होगी। Share/Export में Android Sharesheet खुलेगा।")
                }
            }

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

                if (targetApp !in setOf("sms", "whatsapp")) {
                    result.put("status", "error").put("action", "unsupported_message_channel")
                    result.put("message", "Only SMS and WhatsApp are supported.")
                    return result
                }
                if (contactName.isEmpty() || messageText.isEmpty() || messageText.length > 4000) {
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

                if (!MessageApprovalPolicy.isValidE164(phoneNumber)) {
                    result.put("status", "error").put("action", "invalid_recipient_number")
                    result.put("message", "Contact number must include country code in E.164 format, for example +919876543210.")
                    return result
                }

                val isWhatsApp = targetApp == "whatsapp"
                val channel = if (isWhatsApp) "whatsapp" else "sms"
                if (messageText.isBlank() || messageText.length > 4000) {
                    result.put("status", "error").put("action", "invalid_message_request")
                    result.put("message", "Message must contain 1 to 4000 characters.")
                    return result
                }
                if (openMessageReview(context, channel, phoneNumber, messageText)) {
                    result.put("status", "review_required").put("action", "message_review_opened")
                    result.put("recipient", phoneNumber)
                    result.put("message_preview", messageText)
                    result.put("message", "पूरा recipient और message review screen पर दिखाया गया है। उपयोगकर्ता की अंतिम पुष्टि के बिना कोई संदेश नहीं भेजा जाएगा।")
                } else {
                    result.put("status", "error").put("action", "message_review_unavailable")
                    result.put("message", "Message review screen नहीं खुल सकी; कोई संदेश नहीं भेजा गया।")
                }
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
                                        withinMainBrainTask = withinMainBrainTask
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
                        } else if (!MessageApprovalPolicy.isValidE164(number) || message.length > 4000) {
                            result.put("status", "error").put("action", "invalid_message_request")
                                .put("message", "Recipient must be E.164 and message must be at most 4000 characters.")
                        } else {
                            if (openMessageReview(context, "whatsapp", number, message)) {
                                result.put("status", "review_required").put("action", "message_review_opened")
                                result.put("recipient", number)
                                result.put("message_preview", message)
                                result.put("message", "पूरा WhatsApp recipient और message review screen पर दिखाया गया है। अंतिम पुष्टि के बिना कोई संदेश नहीं भेजा जाएगा।")
                            } else {
                                result.put("status", "error").put("action", "message_review_unavailable")
                                result.put("message", "Message review screen नहीं खुल सकी; कोई WhatsApp संदेश नहीं भेजा गया।")
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

    /** Opens the non-exported final review UI; model-supplied confirmation flags are never trusted. */
    private fun openMessageReview(context: Context, channel: String, recipient: String, message: String): Boolean =
        runCatching {
            context.startActivity(android.content.Intent(context, MessageReviewActivity::class.java).apply {
                putExtra(MessageReviewActivity.EXTRA_CHANNEL, channel)
                putExtra(MessageReviewActivity.EXTRA_RECIPIENT, recipient)
                putExtra(MessageReviewActivity.EXTRA_BODY, message)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.isSuccess

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
