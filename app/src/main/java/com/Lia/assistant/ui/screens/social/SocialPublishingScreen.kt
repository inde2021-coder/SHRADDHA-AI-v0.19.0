package com.Lia.assistant.ui.screens.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.social.SocialPublishingClient
import kotlinx.coroutines.launch
import java.time.Instant
import org.json.JSONArray

@Composable
fun SocialPublishingScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    val platforms = listOf("telegram", "mastodon", "bluesky", "linkedin", "facebook", "instagram")
    var platform by remember { mutableStateOf("telegram") }
    var text by remember { mutableStateOf("") }
    var scheduledAt by remember { mutableStateOf(Instant.now().plusSeconds(3600).toString()) }
    var mediaKind by remember { mutableStateOf("image") }
    var mediaUrl by remember { mutableStateOf("") }
    var mediaBase64 by remember { mutableStateOf("") }
    var mediaMimeType by remember { mutableStateOf("") }
    var mediaFileName by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a platform, review the exact post, then publish now or schedule it.") }
    var scheduledPosts by remember { mutableStateOf<JSONArray?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                val result = runCatching {
                    if (platform == "instagram" || platform == "facebook") {
                        val url = SocialPublishingClient.uploadMedia(context, uri, platform, mediaKind)
                        mediaUrl = url
                        mediaBase64 = ""
                        mediaMimeType = ""
                        mediaFileName = ""
                        status = "Media uploaded to your Firebase storage. Meta receives its URL only when you confirm publishing."
                    } else {
                        val payload = SocialPublishingClient.encodeBinaryMedia(context, uri, platform, mediaKind)
                        mediaBase64 = payload.getString("mediaBase64")
                        mediaMimeType = payload.getString("mimeType")
                        mediaFileName = payload.getString("fileName")
                        mediaUrl = ""
                        status = "Media prepared locally (${(mediaBase64.length * 3L / 4L / 1024L)} KiB). It is sent to the selected provider only after final confirmation."
                    }
                }
                result.onFailure { status = "Media preparation failed: " + (it.message ?: "unknown error") }
                busy = false
            }
        }
    }

    fun loadPosts() {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { SocialPublishingClient.list(context) }
            result.onSuccess { scheduledPosts = it.optJSONArray("posts") ?: JSONArray(); status = "Loaded scheduled post history." }
                .onFailure { status = "Could not load schedule: ${it.message}" }
            busy = false
        }
    }

    fun cancelPost(id: String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { SocialPublishingClient.cancel(context, id) }
            status = result.fold({ "Scheduled post cancelled." }, { "Cancel failed: ${it.message}" })
            busy = false
            loadPosts()
        }
    }

    fun run(action: String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching {
                when (action) {
                    "publish" -> SocialPublishingClient.publish(
                        context, platform, text.trim(),
                        mediaKind.takeIf { platform in setOf("instagram", "facebook") || (platform in setOf("telegram", "mastodon") && mediaBase64.isNotBlank()) },
                        mediaUrl.trim().takeIf { platform == "instagram" || platform == "facebook" },
                        mediaBase64.takeIf { it.isNotBlank() },
                        mediaMimeType.takeIf { it.isNotBlank() },
                        mediaFileName.takeIf { it.isNotBlank() }
                    ).toString()
                    else -> SocialPublishingClient.schedule(context, platform, text.trim(), scheduledAt.trim(), mediaKind = mediaKind.takeIf { platform == "instagram" || platform == "facebook" }, mediaUrl = mediaUrl.trim().takeIf { platform == "instagram" || platform == "facebook" }).toString()
                }
            }
            status = result.fold(
                {
                    if (action == "publish") {
                        mediaBase64 = ""
                        mediaMimeType = ""
                        mediaFileName = ""
                        "Provider accepted the post: $it"
                    } else "Scheduled post saved: $it"
                },
                { "Not submitted: ${it.message ?: "unknown error"}" }
            )
            busy = false
            pendingAction = ""
        }
    }

    Column(Modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NovaTopBar(title = "SOCIAL PUBLISHER", navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
        })
        NovaGlassCard {
            Text("PLATFORM", color = colors.accent)
            platforms.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        FilterChip(selected = platform == item, onClick = { platform = item; mediaUrl = ""; mediaBase64 = ""; mediaMimeType = ""; mediaFileName = ""; mediaKind = "image" }, label = { Text(item.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            OutlinedTextField(value = text, onValueChange = { text = it.take(5000) }, label = { Text("Post text") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
            Text("${text.length}/5000 characters", color = colors.textSecondary)
            if (platform == "instagram" || platform == "facebook") {
                Text(if (platform == "instagram") "Instagram media type" else "Facebook Page media type", color = colors.accent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (platform == "instagram") listOf("image", "reel", "story_image", "story_video") else listOf("image", "video")).forEach { kind ->
                        FilterChip(selected = mediaKind == kind, onClick = { mediaKind = kind }, label = { Text(kind.replace('_', ' ').replaceFirstChar { it.uppercase() }) })
                    }
                }
                OutlinedTextField(value = mediaUrl, onValueChange = { mediaUrl = it.take(2048) }, label = { Text("Public HTTPS media URL") }, modifier = Modifier.fillMaxWidth())
                NovaButton(text = if (busy) "UPLOADING…" else "SELECT & UPLOAD MEDIA", onClick = {
                    val types = if (mediaKind in setOf("image", "story_image")) arrayOf("image/jpeg", "image/png", "image/webp") else arrayOf("video/mp4")
                    mediaPicker.launch(types)
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
                Text("Maximum 100 MiB. Files are stored under your Firebase account; the download URL is shared with the selected social provider only when you publish or schedule.", color = colors.textSecondary)
                Text("Meta must be able to fetch this public HTTPS URL. Instagram supports Feed, Reel and Story; Facebook Pages support image and video posts. Credentials stay on the backend.", color = colors.textSecondary)
            }
        }
        if (platform == "telegram" || platform == "mastodon") {
            NovaGlassCard {
                Text("POST MEDIA", color = colors.accent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("image", "video").forEach { kind ->
                        FilterChip(selected = mediaKind == kind, onClick = {
                            mediaKind = kind
                            mediaBase64 = ""
                            mediaMimeType = ""
                            mediaFileName = ""
                            status = "Choose a new ${kind} file to attach."
                        }, label = { Text(kind.replaceFirstChar { it.uppercase() }) })
                    }
                }
                NovaButton(text = if (busy) "PREPARING…" else "SELECT IMAGE / VIDEO (MAX 8 MiB)", onClick = {
                    val types = if (mediaKind == "image" && platform == "telegram") arrayOf("image/jpeg", "image/png")
                        else if (mediaKind == "image") arrayOf("image/jpeg", "image/png", "image/webp")
                        else if (platform == "telegram") arrayOf("video/mp4") else arrayOf("video/mp4", "video/quicktime")
                    mediaPicker.launch(types)
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
                Text(
                    if (mediaBase64.isNotBlank()) "Attached: ${mediaFileName} • ${mediaMimeType}" else "Image/video is encoded on-device and uploaded only after you confirm the exact post.",
                    color = colors.textSecondary
                )
                Text("Binary media is limited to 8 MiB for Telegram and Mastodon. Scheduled binary-media posts are not supported yet.", color = colors.textSecondary)
            }
        }

        NovaGlassCard {
            Text("DURABLE SCHEDULE", color = colors.accent)
            OutlinedTextField(value = scheduledAt, onValueChange = { scheduledAt = it.take(48) }, label = { Text("ISO-8601 date/time with timezone") }, modifier = Modifier.fillMaxWidth())
            Text("Use ISO-8601 with a timezone. Instagram Feed/Reel/Story and Facebook Page image/video scheduling use the selected media type and a public HTTPS media URL. Telegram and Mastodon currently schedule text-only posts; attached binary media must be published immediately. Scheduling requires Firebase sign-in and backend Firebase Admin credentials. Ambiguous provider outcomes are not blindly retried.", color = colors.textSecondary)
        }
        NovaGlassCard {
            Text(status, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaButton(text = "REFRESH SCHEDULE", onClick = { loadPosts() }, enabled = !busy, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
            }
            scheduledPosts?.let { posts ->
                for (i in 0 until posts.length()) {
                    val post = posts.optJSONObject(i) ?: continue
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${post.optString("platform").uppercase()} • ${post.optString("status").uppercase()}", color = colors.accent)
                        Text(post.optString("scheduledAt"), color = colors.textSecondary)
                        if (post.optString("mediaKind").isNotBlank()) Text("Media: ${post.optString("mediaKind")} • ${post.optString("mediaUrl")}", color = colors.textSecondary)
                        Text(post.optString("text"), color = colors.textPrimary)
                        if (post.optString("status") == "pending") {
                            NovaButton(text = "CANCEL SCHEDULED POST", onClick = { cancelPost(post.optString("id")) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaButton(text = "PUBLISH NOW", onClick = { pendingAction = "publish" }, enabled = !busy && text.isNotBlank() && ( platform !in setOf("instagram", "facebook") || mediaUrl.startsWith("https://") && mediaKind in if (platform == "instagram") setOf("image", "reel", "story_image", "story_video") else setOf("image", "video")), modifier = Modifier.weight(1f))
                NovaButton(text = "SCHEDULE", onClick = { pendingAction = "schedule" }, enabled = !busy && text.isNotBlank() && mediaBase64.isBlank() && (platform !in setOf("instagram", "facebook") || mediaUrl.startsWith("https://") && mediaKind in if (platform == "instagram") setOf("image", "reel", "story_image", "story_video") else setOf("image", "video")), modifier = Modifier.weight(1f), type = NovaButtonType.SECONDARY)
            }
        }
    }

    if (pendingAction.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingAction = "" },
            title = { Text(if (pendingAction == "publish") "Confirm exact post" else "Confirm scheduled post") },
            text = { Text(
                "Platform: ${platform.uppercase()}\n" +
                    (if (platform == "instagram" || platform == "facebook") "Media: $mediaKind\nURL: $mediaUrl\n" else if (mediaBase64.isNotBlank()) "Attachment: $mediaKind • $mediaFileName • $mediaMimeType\n" else "") +
                    "\n${text.trim()}\n\n" +
                    (if (pendingAction == "schedule") "Scheduled at: $scheduledAt" else "Publish immediately")
            ) },
            confirmButton = { TextButton(onClick = { run(pendingAction) }, enabled = !busy) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { pendingAction = "" }, enabled = !busy) { Text("Cancel") } }
        )
    }
}
