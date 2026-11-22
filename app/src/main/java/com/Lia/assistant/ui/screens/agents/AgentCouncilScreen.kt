package com.Lia.assistant.ui.screens.agents

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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import `in`.shraddha.ai.future.BackgroundTaskQueue
import `in`.shraddha.ai.future.CouncilEvidenceStore
import `in`.shraddha.ai.future.CouncilExecutionBudget
import `in`.shraddha.ai.future.MainBrainProviderGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private const val LAST_COUNCIL_WORK_ID = "council_last_work_id"

@Composable
fun AgentCouncilScreen(
    onBack: () -> Unit,
    onOpenApiKeys: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var goal by remember { mutableStateOf("") }
    var configuredProviders by remember { mutableStateOf(emptyList<String>()) }
    var selectedProviders by remember { mutableStateOf(setOf<String>()) }
    var language by remember { mutableStateOf("AUTO") }
    var specialists by remember { mutableStateOf(3f) }
    var maxCalls by remember { mutableStateOf(9f) }
    var callsPerProvider by remember { mutableStateOf(9f) }
    var durationSeconds by remember { mutableStateOf(300f) }
    var workId by remember { mutableStateOf<UUID?>(null) }
    var workInfo by remember { mutableStateOf<WorkInfo?>(null) }
    var stageEvidence by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        configuredProviders = withContext(Dispatchers.IO) {
            runCatching { MainBrainProviderGateway.configuredProviders(context.applicationContext, preferredProvider = "GEMINI") }
                .getOrDefault(emptyList())
        }
        selectedProviders = configuredProviders.take(1).toSet()
        val savedId = withContext(Dispatchers.IO) {
            EncryptedFeatureStore(context.applicationContext).get(LAST_COUNCIL_WORK_ID).getOrDefault("")
        }
        workId = runCatching { savedId.takeIf { it.isNotBlank() }?.let(UUID::fromString) }.getOrNull()
    }

    DisposableEffect(workId, lifecycleOwner) {
        val id = workId
        if (id == null) {
            workInfo = null
            onDispose { }
        } else {
            val liveData = WorkManager.getInstance(context.applicationContext).getWorkInfoByIdLiveData(id)
            val observer = Observer<WorkInfo?> { info -> workInfo = info }
            liveData.observe(lifecycleOwner, observer)
            onDispose { liveData.removeObserver(observer) }
        }
    }

    LaunchedEffect(workId, workInfo?.state, workInfo?.progress?.getInt(BackgroundTaskQueue.KEY_PROGRESS_INDEX, 0)) {
        val id = workId ?: return@LaunchedEffect
        val taskId = "bg_" + id.toString().replace("-", "")
        stageEvidence = withContext(Dispatchers.IO) {
            CouncilEvidenceStore(context.applicationContext).readTask(taskId).getOrDefault(emptyList())
                .sortedBy { it.optLong("completedAt", it.optLong("updatedAt", 0L)) }
        }
    }

    val minimumCalls = specialists.toInt() + 4
    val isRunning = workInfo?.state == WorkInfo.State.RUNNING ||
        workInfo?.state == WorkInfo.State.ENQUEUED ||
        workInfo?.state == WorkInfo.State.BLOCKED
    val statusColor = when (workInfo?.state) {
        WorkInfo.State.SUCCEEDED -> Color(0xFF58D6A5)
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Color(0xFFFF8585)
        WorkInfo.State.RUNNING -> Color(0xFF52D7F2)
        else -> colors.textSecondary
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(
            title = "MULTI-AGENT WORKSPACE",
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.textPrimary
                    )
                }
            }
        )

        NovaGlassCard(cornerRadius = 22.dp, elevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Default.Groups, null, tint = colors.accent)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Shraddha Main Brain", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Planner → Researcher → Specialists → Critic → Verifier", color = colors.textSecondary, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("काम का लक्ष्य", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            NovaTextField(
                value = goal,
                onValueChange = { goal = it.take(2_000) },
                modifier = Modifier.fillMaxWidth().height(112.dp),
                placeholder = "Shraddha को बताएं कि कौन-सा काम पूरा करना है…",
                singleLine = false
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${goal.length}/2000", color = colors.textTertiary, fontSize = 10.sp)
            }
        }

        NovaGlassCard {
            Text("AI PROVIDERS", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (configuredProviders.isEmpty()) {
                Text("अभी कोई Provider API key configured नहीं मिली है। पहले अपनी API key जोड़ें।", color = colors.textSecondary, fontSize = 12.sp)
                NovaButton("Open API Keys", onOpenApiKeys, type = NovaButtonType.SECONDARY)
            } else {
                configuredProviders.forEach { provider ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = provider in selectedProviders,
                            onCheckedChange = { checked ->
                                val next = if (checked) selectedProviders + provider else selectedProviders - provider
                                selectedProviders = next
                                if (next.isNotEmpty()) {
                                    val requiredPerProvider = ((specialists.toInt() + 4) + next.size - 1) / next.size
                                    callsPerProvider = maxOf(callsPerProvider, requiredPerProvider.toFloat())
                                }
                            }
                        )
                        Text(provider, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("KEY READY", color = Color(0xFF58D6A5), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("Selected providers are assigned round-robin to the real specialist calls.", color = colors.textTertiary, fontSize = 10.sp)
        }

        NovaGlassCard {
            Text("EXECUTION LIMITS", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Specialist agents: ${specialists.toInt()}", color = colors.textPrimary, fontSize = 13.sp)
            Slider(value = specialists, onValueChange = {
                specialists = it
                maxCalls = maxOf(maxCalls, it.toInt() + 4).coerceAtMost(9).toFloat()
                if (selectedProviders.isNotEmpty()) {
                    val requiredPerProvider = ((it.toInt() + 4) + selectedProviders.size - 1) / selectedProviders.size
                    callsPerProvider = maxOf(callsPerProvider, requiredPerProvider.toFloat())
                }
            }, valueRange = 1f..5f, steps = 3)
            Text("Total provider calls: ${maxCalls.toInt()} (minimum required: $minimumCalls)", color = colors.textPrimary, fontSize = 12.sp)
            if (minimumCalls < 9) {
                Slider(
                    value = maxCalls.coerceAtLeast(minimumCalls.toFloat()),
                    onValueChange = { maxCalls = it },
                    valueRange = minimumCalls.toFloat()..9f,
                    steps = (9 - minimumCalls - 1).coerceAtLeast(0)
                )
            } else {
                Text("All nine calls are required for five specialists.", color = colors.textTertiary, fontSize = 10.sp)
            }
            val minimumPerProvider = if (selectedProviders.isEmpty()) 1 else
                ((specialists.toInt() + 4) + selectedProviders.size - 1) / selectedProviders.size
            Text("Maximum calls per provider: ${callsPerProvider.toInt().coerceAtLeast(minimumPerProvider)} (minimum required: $minimumPerProvider)", color = colors.textPrimary, fontSize = 12.sp)
            if (minimumPerProvider < 9) {
                Slider(
                    value = callsPerProvider.coerceAtLeast(minimumPerProvider.toFloat()),
                    onValueChange = { callsPerProvider = it },
                    valueRange = minimumPerProvider.toFloat()..9f,
                    steps = (9 - minimumPerProvider - 1).coerceAtLeast(0)
                )
            } else {
                Text("All nine calls must be allowed for the selected provider set.", color = colors.textTertiary, fontSize = 10.sp)
            }
            Text("Time limit: ${durationSeconds.toInt() / 60} min ${durationSeconds.toInt() % 60} sec", color = colors.textPrimary, fontSize = 12.sp)
            Slider(value = durationSeconds, onValueChange = { durationSeconds = it }, valueRange = 30f..600f, steps = 18)
            Text("प्रत्येक Agent का output अधिकतम 9,000 characters तक सीमित रहेगा।", color = colors.textTertiary, fontSize = 10.sp)
        }

        NovaGlassCard {
            Text("RESPONSE LANGUAGE", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO", "Hindi", "English").forEach { option ->
                    FilterChip(
                        selected = language == option,
                        onClick = { language = option },
                        label = { Text(option) }
                    )
                }
            }
        }

        if (errorMessage.isNotBlank()) {
            NovaGlassCard {
                Text(errorMessage, color = Color(0xFFFF9A9A), fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            NovaButton(
                text = if (isSubmitting) "Starting…" else "Start Agent Team",
                onClick = {
                    if (goal.isBlank()) {
                        errorMessage = "पहले काम का लक्ष्य लिखें।"
                    } else if (selectedProviders.isEmpty()) {
                        errorMessage = "कम से कम एक configured AI provider चुनें।"
                    } else if (isRunning) {
                        errorMessage = "पहले चल रहे task को पूरा या cancel करें।"
                    } else {
                        errorMessage = ""
                        isSubmitting = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    BackgroundTaskQueue.enqueue(
                                        context = context.applicationContext,
                                        goal = goal,
                                        provider = selectedProviders.sorted().joinToString(","),
                                        language = language,
                                        specialistCount = specialists.toInt(),
                                        budget = CouncilExecutionBudget(
                                            maxProviderCalls = maxCalls.toInt().coerceAtLeast(minimumCalls),
                                            maxCallsPerProvider = callsPerProvider.toInt().coerceAtLeast(minimumPerProvider),
                                            maxDurationMs = durationSeconds.toLong() * 1_000L,
                                            maxOutputCharactersPerStage = 9_000
                                        )
                                    )
                                }
                            }
                            result.onSuccess { id ->
                                workId = id
                                EncryptedFeatureStore(context.applicationContext).put(LAST_COUNCIL_WORK_ID, id.toString())
                                    .onFailure { errorMessage = "Task started, but its recovery ID could not be saved: ${it.message}" }
                            }.onFailure { errorMessage = it.message ?: "Could not start the agent team." }
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting && !isRunning && configuredProviders.isNotEmpty() && selectedProviders.isNotEmpty(),
                modifier = Modifier.weight(1f)
            )
            if (isRunning && workId != null) {
                NovaButton(
                    text = "Cancel",
                    onClick = {
                        workId?.let { BackgroundTaskQueue.cancel(context.applicationContext, it) }
                    },
                    type = NovaButtonType.SECONDARY
                )
            }
        }

        workInfo?.let { info ->
            NovaGlassCard {
                Text("TASK STATUS", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(info.state.name, color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                val role = info.progress.getString(BackgroundTaskQueue.KEY_PROGRESS_ROLE).orEmpty()
                val index = info.progress.getInt(BackgroundTaskQueue.KEY_PROGRESS_INDEX, 0)
                val total = info.progress.getInt(BackgroundTaskQueue.KEY_PROGRESS_TOTAL, 0)
                if (role.isNotBlank() && total > 0) {
                    Text("Current stage: $role ($index/$total)", color = colors.textSecondary, fontSize = 12.sp)
                }
                stageEvidence.lastOrNull { it.has("status") && !it.has("output") }?.let { status ->
                    val usage = status.optString("providerUsage")
                    if (usage.isNotBlank()) {
                        Text("Provider calls: $usage", color = colors.textSecondary, fontSize = 11.sp)
                    }
                    if (status.has("elapsedMs")) {
                        Text("Elapsed: ${status.optLong("elapsedMs") / 1000}s", color = colors.textTertiary, fontSize = 10.sp)
                    }
                }
                info.outputData.getString(BackgroundTaskQueue.KEY_FINAL_REPORT)?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    Text("VERIFIER REPORT", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(report, color = colors.textPrimary, fontSize = 12.sp)
                }
                info.outputData.getString(BackgroundTaskQueue.KEY_ERROR)?.let { error ->
                    Text(error, color = Color(0xFFFF9999), fontSize = 12.sp)
                }
            }
        }

        val completedStages = stageEvidence.filter { it.has("output") }
        if (completedStages.isNotEmpty()) {
            Text("SAVED STAGE EVIDENCE", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            completedStages.forEach { stage ->
                NovaGlassCard(cornerRadius = 17.dp, elevation = 3.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stage.optString("role"), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(stage.optString("provider"), color = colors.accent, fontSize = 10.sp)
                    }
                    Text(stage.optString("output"), color = colors.textSecondary, fontSize = 11.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Text("Attempt ${stage.optInt("attempt", 0) + 1}", color = colors.textTertiary, fontSize = 9.sp)
                }
            }
        }

        workId?.let { id ->
            val taskId = "bg_" + id.toString().replace("-", "")
            NovaButton(
                text = "Clear saved task evidence",
                onClick = {
                    CouncilEvidenceStore(context.applicationContext).removeTask(taskId)
                    EncryptedFeatureStore(context.applicationContext).remove(LAST_COUNCIL_WORK_ID)
                    stageEvidence = emptyList()
                    workInfo = null
                    workId = null
                },
                type = NovaButtonType.TEXT,
                enabled = workInfo?.state?.isFinished == true
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
