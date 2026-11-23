package com.Lia.assistant.ui.screens.expert

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.link.DeviceLinkManager
import `in`.shraddha.ai.future.ExpertProjectLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EXPERT_PROVIDERS = listOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")
private val EXPERT_DOMAINS = listOf(
    "electronics", "electrical", "mechanical", "medical", "agriculture",
    "civil", "law", "repair", "creator", "general"
)
private val EXPERT_MODES = listOf("learn", "solve", "design", "diagnose", "exam", "create")

private fun apiProviderKey(label: String): String = when (label.uppercase()) {
    "CLAUDE" -> "anthropic"
    else -> label.lowercase()
}

private fun studioWorkspaceKey(domain: String): String = when (domain) {
    "medical" -> "medical_education"
    "civil" -> "civil_building"
    "general" -> "research"
    else -> domain
}

private fun studioTaskKey(mode: String): String = when (mode) {
    "learn" -> "teach"
    "exam" -> "quiz"
    else -> mode
}

private fun formatExpertResponse(raw: String): String {
    val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return raw
    val answer = json.optString("answer")
    if (answer.isNotBlank()) return answer
    val results = json.optJSONArray("results") ?: return raw
    return (0 until results.length()).mapNotNull { index ->
        val item = results.optJSONObject(index) ?: return@mapNotNull null
        val providerName = item.optString("provider").uppercase()
        val content = item.optString("answer").ifBlank { item.optString("error", "No result returned.") }
        "### $providerName\n$content"
    }.joinToString("\n\n").ifBlank { raw }
}

@Composable
fun ExpertStudioScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    var gatewayConfigured by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf("GEMINI") }
    var compareSelection by remember { mutableStateOf(setOf("GEMINI", "OPENAI")) }
    var domain by remember { mutableStateOf("general") }
    var mode by remember { mutableStateOf("learn") }
    var question by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var library by remember { mutableStateOf(emptyList<ExpertProjectLibrary.Entry>()) }
    var expandedEntry by remember { mutableStateOf<ExpertProjectLibrary.Entry?>(null) }

    LaunchedEffect(Unit) {
        gatewayConfigured = withContext(Dispatchers.IO) { VoiceGatewaySettings.isConfigured(context) }
        library = withContext(Dispatchers.IO) {
            ExpertProjectLibrary(context).load().getOrDefault(emptyList())
        }
        val preferred = NovaPreferences.getString(context, NovaPreferences.Keys.AI_PROVIDER, "GEMINI").trim().uppercase()
        provider = preferred.takeIf { it in EXPERT_PROVIDERS } ?: EXPERT_PROVIDERS.first()
    }

    fun saveResult(title: String, content: String, workspace: String, task: String, usedProvider: String) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                ExpertProjectLibrary(context).save(title, content, workspace, task, usedProvider)
            }
            mainHandler.post {
                saved.onSuccess { library = it; status = "Result saved to encrypted Expert Project Library." }
                    .onFailure { status = "Result received, but secure library save failed: ${it.message}" }
            }
        }
    }

    fun runExpert() {
        if (!gatewayConfigured) {
            status = "Secure backend URL and gateway token must be configured in Settings."
            return
        }
        if (question.isBlank()) {
            status = "Write a question or task first."
            return
        }
        if (isBusy) return
        isBusy = true
        response = ""
        status = "Sending a real request to the configured Expert API…"
        val config = VoiceGatewaySettings.get(context)
        DeviceLinkManager.expertAssist(
            config.baseUrl, config.gatewayToken, apiProviderKey(provider), domain, mode, question.trim()
        ) { result ->
            result.onSuccess { rawAnswer ->
                val answer = formatExpertResponse(rawAnswer)
                mainHandler.post {
                    response = answer
                    status = "Expert response received from the configured backend."
                    isBusy = false
                }
                saveResult(question.trim().take(80).ifBlank { "Expert result" }, answer, domain, mode, provider)
            }.onFailure { error ->
                mainHandler.post {
                    status = error.message ?: "Expert API request failed."
                    isBusy = false
                }
            }
        }
    }

    fun runStudio() {
        if (!gatewayConfigured) {
            status = "Secure backend URL and gateway token must be configured in Settings."
            return
        }
        if (question.isBlank()) {
            status = "Write a project request first."
            return
        }
        if (isBusy) return
        isBusy = true
        response = ""
        status = "Running a real Studio Work request…"
        val config = VoiceGatewaySettings.get(context)
        DeviceLinkManager.studioWork(
            config.baseUrl, config.gatewayToken, apiProviderKey(provider), studioWorkspaceKey(domain), studioTaskKey(mode), question.trim()
        ) { result ->
            result.onSuccess { rawAnswer ->
                val answer = formatExpertResponse(rawAnswer)
                mainHandler.post {
                    response = answer
                    status = "Studio output received from the configured backend."
                    isBusy = false
                }
                saveResult(question.trim().take(80).ifBlank { "Studio result" }, answer, studioWorkspaceKey(domain), studioTaskKey(mode), provider)
            }.onFailure { error ->
                mainHandler.post {
                    status = error.message ?: "Studio API request failed."
                    isBusy = false
                }
            }
        }
    }

    fun compareSelectedProviders() {
        if (!gatewayConfigured) {
            status = "Secure backend URL and gateway token must be configured in Settings."
            return
        }
        if (question.isBlank() || compareSelection.size < 2) {
            status = "Choose at least two providers and enter a question to compare."
            return
        }
        if (isBusy) return
        isBusy = true
        response = ""
        status = "Comparing selected providers through the configured backend…"
        val config = VoiceGatewaySettings.get(context)
        DeviceLinkManager.compareProviders(
            config.baseUrl, config.gatewayToken, compareSelection.sorted().map(::apiProviderKey), question.trim()
        ) { result ->
            result.onSuccess { rawAnswer ->
                val answer = formatExpertResponse(rawAnswer)
                mainHandler.post {
                    response = answer
                    status = "Provider comparison received."
                    isBusy = false
                }
                saveResult(question.trim().take(80).ifBlank { "Provider comparison" }, answer, "comparison", "compare", compareSelection.sorted().joinToString(","))
            }.onFailure { error ->
                mainHandler.post {
                    status = error.message ?: "Provider comparison failed."
                    isBusy = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(
            title = "EXPERT STUDIO",
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
            }
        )

        NovaGlassCard(cornerRadius = 22.dp, elevation = 6.dp) {
            NovaButton(text = "OPEN ENGINEERING WORKBENCH", onClick = { navController.navigate("engineering_workbench") }, type = NovaButtonType.SECONDARY, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = colors.accent)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Expert Course • Repair • Creator Studio", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Real backend requests • encrypted local project library", color = colors.textSecondary, fontSize = 10.sp)
                }
            }
            Text(
                if (gatewayConfigured) "Secure backend configured" else "Backend not configured",
                color = if (gatewayConfigured) Color(0xFF58D6A5) else Color(0xFFFFB86B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (!gatewayConfigured) {
                Text("Expert tools do not run in demo mode. Add the HTTPS backend URL and gateway token in Settings first.", color = colors.textSecondary, fontSize = 11.sp)
                NovaButton("Open Settings", onClick = { navController.navigate("settings") }, type = NovaButtonType.SECONDARY)
            }
            NovaButton(
                text = "Refresh Backend Status",
                onClick = {
                    scope.launch {
                        gatewayConfigured = withContext(Dispatchers.IO) { VoiceGatewaySettings.isConfigured(context) }
                    }
                },
                type = NovaButtonType.TEXT
            )
        }

        NovaGlassCard {
            Text("AI PROVIDER", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EXPERT_PROVIDERS.take(3).forEach { item ->
                    FilterChip(selected = provider == item, onClick = { provider = item }, label = { Text(item) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EXPERT_PROVIDERS.drop(3).forEach { item ->
                    FilterChip(selected = provider == item, onClick = { provider = item }, label = { Text(item) })
                }
            }
            Text("The selected provider must also be enabled on your secure backend.", color = colors.textTertiary, fontSize = 10.sp)
            Text("Medical output is educational only; legal output is general information; engineering/civil work is conceptual and is not professional certification.", color = colors.textSecondary, fontSize = 10.sp)
        }

        NovaGlassCard {
            Text("EXPERT DOMAIN", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            EXPERT_DOMAINS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { item ->
                        FilterChip(
                            selected = domain == item,
                            onClick = { domain = item },
                            label = { Text(item.replaceFirstChar { it.uppercase() }, fontSize = 10.sp) }
                        )
                    }
                }
            }
            Text("MODE", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EXPERT_MODES.take(3).forEach { item ->
                    FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EXPERT_MODES.drop(3).forEach { item ->
                    FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item) })
                }
            }
            NovaTextField(
                value = question,
                onValueChange = { question = it.take(16_000) },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                placeholder = "अपना सवाल, project brief या troubleshooting request लिखें…",
                singleLine = false
            )
            Text("${question.length}/16000", color = colors.textTertiary, fontSize = 10.sp)
        }

        NovaGlassCard {
            Text("RUN REAL EXPERT TASK", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaButton(
                    text = if (isBusy) "Working…" else "Expert Assist",
                    onClick = { runExpert() },
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy && gatewayConfigured
                )
                NovaButton(
                    text = "Studio Work",
                    onClick = { runStudio() },
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy && gatewayConfigured,
                    type = NovaButtonType.SECONDARY
                )
            }
            Text("COMPARE PROVIDERS (UP TO 3)", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            EXPERT_PROVIDERS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        FilterChip(
                            selected = item in compareSelection,
                            onClick = {
                                compareSelection = when {
                                    item in compareSelection -> compareSelection - item
                                    compareSelection.size < 3 -> compareSelection + item
                                    else -> {
                                        status = "Provider comparison supports at most three providers per request."
                                        compareSelection
                                    }
                                }
                            },
                            label = { Text(item, fontSize = 9.sp) }
                        )
                    }
                }
            }
            NovaButton(
                text = "Compare Selected Providers",
                onClick = { compareSelectedProviders() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy && gatewayConfigured && compareSelection.size >= 2,
                type = NovaButtonType.SECONDARY
            )
        }

        if (status.isNotBlank()) {
            NovaGlassCard {
                Text(status, color = colors.textSecondary, fontSize = 11.sp)
            }
        }

        if (response.isNotBlank()) {
            NovaGlassCard {
                Text("LATEST RESULT", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(response, color = colors.textPrimary, fontSize = 12.sp)
                Text("Saved locally when the secure library write succeeds.", color = colors.textTertiary, fontSize = 9.sp)
            }
        }

        NovaGlassCard {
            Text("ENCRYPTED PROJECT LIBRARY", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (library.isEmpty()) {
                Text("No saved Expert Studio results yet.", color = colors.textSecondary, fontSize = 11.sp)
            } else {
                library.forEach { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(entry.title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${entry.workspace} • ${entry.task} • ${entry.provider}", color = colors.textTertiary, fontSize = 9.sp)
                        Text(entry.content, color = colors.textSecondary, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { expandedEntry = entry }) { Text("Open") }
                            TextButton(onClick = {
                                scope.launch {
                                    val updated = withContext(Dispatchers.IO) { ExpertProjectLibrary(context).delete(entry.id) }
                                    updated.onSuccess { library = it }.onFailure { status = it.message ?: "Could not delete saved result." }
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    expandedEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { expandedEntry = null },
            title = { Text(entry.title) },
            text = { Text(entry.content) },
            confirmButton = { TextButton(onClick = { expandedEntry = null }) { Text("Close") } }
        )
    }
}
