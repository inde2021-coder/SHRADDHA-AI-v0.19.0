package com.Lia.assistant.ui.screens.permissions

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky

private enum class AccessState { GRANTED, DENIED, NOT_AVAILABLE }

private data class PermissionRow(
    val id: String,
    val name: String,
    val detail: String,
    val state: AccessState,
    val runtimePermission: String? = null,
    val settingsAction: String? = null
)

@Composable
fun PermissionsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableStateOf(0) }

    fun refresh() { refreshKey++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }
    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "SMS अनुमति मिल गई।" else "SMS अनुमति अस्वीकृत है। Android/Play policy इसे रोक सकती है; Settings में उपलब्ध हो तो अनुमति बदलें या SMS ऐप से भेजें।",
            Toast.LENGTH_LONG
        ).show()
        refresh()
    }

    val rows = remember(context, refreshKey) { buildPermissionRows(context) }

    fun openSettings(action: String?) {
        val intent = when (action) {
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            else -> return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky().padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Column {
                Text("Permission dashboard", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Live status • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    color = colors.textSecondary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Only grant access to features you use. Special access opens Android Settings; runtime permissions use the Android permission prompt.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.id }) { row ->
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            when {
                                row.runtimePermission == Manifest.permission.SEND_SMS -> {
                                    val prefs = context.getSharedPreferences("permission_requests", Context.MODE_PRIVATE)
                                    val alreadyAsked = prefs.getBoolean("sms_requested_before", false)
                                    val activity = context as? Activity
                                    val permanentlyDenied = alreadyAsked && activity != null &&
                                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.SEND_SMS)
                                    if (permanentlyDenied) {
                                        Toast.makeText(context, "SMS permission पहले अस्वीकार हो चुकी है। App info > Permissions में जाकर उपलब्ध हो तो Allow करें।", Toast.LENGTH_LONG).show()
                                        openSettings("app")
                                    } else {
                                        prefs.edit().putBoolean("sms_requested_before", true).apply()
                                        smsLauncher.launch(Manifest.permission.SEND_SMS)
                                    }
                                }
                                row.runtimePermission != null -> runtimeLauncher.launch(row.runtimePermission)
                                row.settingsAction != null -> openSettings(row.settingsAction)
                            }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(11.dp).clip(CircleShape).background(
                                when (row.state) {
                                    AccessState.GRANTED -> colors.lagoon
                                    AccessState.DENIED -> colors.error
                                    AccessState.NOT_AVAILABLE -> colors.textSecondary
                                }
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(row.detail, color = colors.textSecondary, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                row.state == AccessState.GRANTED -> "Granted"
                                row.state == AccessState.DENIED && row.runtimePermission != null -> "Allow"
                                row.state == AccessState.DENIED -> "Enable"
                                else -> "Not available"
                            },
                            color = when (row.state) {
                                AccessState.GRANTED -> colors.lagoon
                                AccessState.DENIED -> colors.error
                                AccessState.NOT_AVAILABLE -> colors.textSecondary
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth().clickable { openSettings("app") }) {
                    Text("Open app settings", color = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun buildPermissionRows(context: Context): List<PermissionRow> {
    val pm = context.packageManager
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun runtime(id: String, name: String, detail: String, permission: String, minApi: Int = 1, maxApi: Int = Int.MAX_VALUE): PermissionRow {
        if (Build.VERSION.SDK_INT !in minApi..maxApi) return PermissionRow(id, name, detail, AccessState.NOT_AVAILABLE)
        return PermissionRow(id, name, detail, if (granted(permission)) AccessState.GRANTED else AccessState.DENIED, runtimePermission = permission)
    }
    fun declared(permission: String, name: String, detail: String, available: Boolean = true): PermissionRow {
        if (!available) return PermissionRow(permission, name, detail, AccessState.NOT_AVAILABLE)
        val status = if (pm.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
            AccessState.GRANTED else AccessState.DENIED
        return PermissionRow(permission, name, detail, status)
    }
    fun special(id: String, name: String, detail: String, enabled: Boolean, action: String) =
        PermissionRow(id, name, detail, if (enabled) AccessState.GRANTED else AccessState.DENIED, settingsAction = action)

    val overlay = if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else true
    val enabledAccessibilityServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    fun serviceEnabled(className: String): Boolean = runCatching {
        enabledAccessibilityServices.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == context.packageName && it.className == className }
    }.getOrDefault(false)
    val shraddhaAccessibility = serviceEnabled("in.shraddha.ai.accessibility.ShraddhaAccessibilityService")
    val liaAccessibility = serviceEnabled("com.Lia.assistant.agent.NovaAccessibilityService")
    val listener = runCatching {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        enabled.split(':').any { it.startsWith(context.packageName + "/") }
    }.getOrDefault(false)
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

    return buildList {
        add(runtime("microphone", "Microphone", "Voice input and live speech recognition", Manifest.permission.RECORD_AUDIO))
        add(runtime("camera", "Camera", "Visual Lens and biometric capture", Manifest.permission.CAMERA))
        add(runtime("contacts", "Contacts", "Resolve people by name", Manifest.permission.READ_CONTACTS))
        add(runtime("call", "Direct phone calls", "Place calls after user confirmation", Manifest.permission.CALL_PHONE))
        add(runtime("sms", "Send SMS", "Send text messages after user confirmation", Manifest.permission.SEND_SMS))
        add(PermissionRow("notifications", "App notifications", "Android notification display permission",
            if (Build.VERSION.SDK_INT >= 33) {
                if (granted(Manifest.permission.POST_NOTIFICATIONS) && notificationsEnabled) AccessState.GRANTED else AccessState.DENIED
            } else if (notificationsEnabled) AccessState.GRANTED else AccessState.DENIED,
            runtimePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
            settingsAction = if (Build.VERSION.SDK_INT < 33) "app" else null))
        add(runtime("images", "Photos and images", "User-selected media access on Android 13+", Manifest.permission.READ_MEDIA_IMAGES, 33))
        add(runtime("videos", "Videos", "User-selected video access on Android 13+", Manifest.permission.READ_MEDIA_VIDEO, 33))
        add(runtime("selected_media", "Selected photos access", "Limited photo access on Android 14+", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, 34))
        add(runtime("legacy_storage", "Legacy shared storage", "Legacy media access on Android 12 and earlier", Manifest.permission.READ_EXTERNAL_STORAGE, 1, 32))
        add(special("overlay", "Display over other apps", "Required only for floating assistant overlay", overlay, "overlay"))
        val accessibilityHelp = if (Build.VERSION.SDK_INT >= 33) "Android 13+ पर switch grey हो तो App info > ⋮ > Allow restricted settings करें, फिर यहाँ लौटकर चालू करें।" else "इसे Android Accessibility Settings में चालू करें।"
        add(special("accessibility", "Shraddha Device Control", "मुख्य on-screen control service. $accessibilityHelp", shraddhaAccessibility, "accessibility"))
        add(special("lia_accessibility", "Live App Operator", "LIA screen interaction और gestures. $accessibilityHelp", liaAccessibility, "accessibility"))
        add(special("screen_reading", "Screen reading & capture", if (Build.VERSION.SDK_INT >= 30) "Android 11+ पर Shraddha Device Control चालू होने पर उपलब्ध; secure screens Android रोक सकता है" else "इस Android version पर Accessibility screenshot API समर्थित नहीं है", Build.VERSION.SDK_INT >= 30 && shraddhaAccessibility, "accessibility"))
        add(special("notification_listener", "Notification access", "Reads notifications only after special access is enabled", listener, "notification_listener"))
        add(declared(Manifest.permission.FOREGROUND_SERVICE, "Foreground services", "Android service runtime permission", Build.VERSION.SDK_INT >= 28))
        add(declared("android.permission.FOREGROUND_SERVICE_MICROPHONE", "Microphone foreground service", "Android 14+ service type permission", Build.VERSION.SDK_INT >= 34))
        add(declared("android.permission.FOREGROUND_SERVICE_CAMERA", "Camera foreground service", "Android 14+ service type permission", Build.VERSION.SDK_INT >= 34))
        add(PermissionRow("background_mic", "Background microphone", "No separate user-grantable permission; Android limits when microphone services can start", AccessState.NOT_AVAILABLE))
    }
}
