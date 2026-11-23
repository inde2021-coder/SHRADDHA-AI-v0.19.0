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

@Composable
fun SocialPublishingScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    val platforms = listOf("telegram", "mastodon", "bluesky", "linkedin")
    var platform by remember { mutableStateOf("telegram") }
    var text by remember { mutableStateOf("") }
    var scheduledAt by remember { mutableStateOf(Instant.now().plusSeconds(3600).toString()) }
    var pendingAction by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a platform, review the exact post, then publish now or schedule it.") }

    fun run(action: String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching {
                when (action) {
                    "publish" -> SocialPublishingClient.publish(context, platform, text.trim()).toString()
                    else -> SocialPublishingClient.schedule(context, platform, text.trim(), scheduledAt.trim()).toString()
                }
            }
            status = result.fold(
                { if (action == "publish") "Provider accepted the post: $it" else "Scheduled post saved: $it" },
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
                        FilterChip(selected = platform == item, onClick = { platform = item }, label = { Text(item.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            OutlinedTextField(value = text, onValueChange = { text = it.take(5000) }, label = { Text("Post text") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
            Text("${text.length}/5000 characters", color = colors.textSecondary)
        }
        NovaGlassCard {
            Text("SCHEDULE (TEXT ONLY)", color = colors.accent)
            OutlinedTextField(value = scheduledAt, onValueChange = { scheduledAt = it.take(48) }, label = { Text("ISO-8601 date/time with timezone") }, modifier = Modifier.fillMaxWidth())
            Text("Example: 2026-11-23T18:30:00+05:30. Scheduling needs Firebase sign-in and backend Firebase Admin credentials. Posts are not retried automatically after an ambiguous provider response.", color = colors.textSecondary)
        }
        NovaGlassCard {
            Text(status, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaButton(text = "PUBLISH NOW", onClick = { pendingAction = "publish" }, enabled = !busy && text.isNotBlank(), modifier = Modifier.weight(1f))
                NovaButton(text = "SCHEDULE", onClick = { pendingAction = "schedule" }, enabled = !busy && text.isNotBlank(), modifier = Modifier.weight(1f), type = NovaButtonType.SECONDARY)
            }
        }
    }

    if (pendingAction.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingAction = "" },
            title = { Text(if (pendingAction == "publish") "Confirm exact post" else "Confirm scheduled post") },
            text = { Text("Platform: ${platform.uppercase()}\\n\\n${text.trim()}\\n\\n" + if (pendingAction == "schedule") "Scheduled at: $scheduledAt" else "Publish immediately") },
            confirmButton = { TextButton(onClick = { run(pendingAction) }, enabled = !busy) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { pendingAction = "" }, enabled = !busy) { Text("Cancel") } }
        )
    }
}
