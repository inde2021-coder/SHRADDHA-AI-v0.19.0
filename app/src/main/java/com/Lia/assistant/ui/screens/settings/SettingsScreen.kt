package com.Lia.assistant.ui.screens.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.VoiceGatewaySettings
import `in`.shraddha.ai.future.CouncilCostBudget
import com.Lia.assistant.voice.ConversationMemory
import `in`.shraddha.ai.operator.VoiceToolActionJournal
import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.components.AccessibilitySettingsRow
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaSettingsRow
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.LiaReferenceTheme

@Composable
fun SettingsScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    LiaReferenceTheme { SettingsScreenContent(navController, appState, modifier) }
}

@Composable
private fun SettingsScreenContent(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showGatewayDialog by remember { mutableStateOf(false) }
    var showCostBudgetDialog by remember { mutableStateOf(false) }
    val initialCostSettings = remember { CouncilCostBudget.settings(context) }
    var monthlyBudgetInput by remember { mutableStateOf(if (initialCostSettings.monthlyLimitUsd > 0) initialCostSettings.monthlyLimitUsd.toString() else "") }
    var modelPricesInput by remember { mutableStateOf(initialCostSettings.pricesJson) }
    var costBudgetError by remember { mutableStateOf<String?>(null) }
    val savedGateway = remember { VoiceGatewaySettings.get(context) }
    var gatewayUrlInput by remember { mutableStateOf(savedGateway.baseUrl.ifBlank { VoiceGatewaySettings.DEFAULT_BASE_URL }) }
    var gatewayTokenInput by remember { mutableStateOf(savedGateway.gatewayToken) }
    var gatewaySaveError by remember { mutableStateOf(savedGateway.readError) }
    var gatewayConfigured by remember { mutableStateOf(VoiceGatewaySettings.isConfigured(context)) }
    var voiceMemoryEnabled by remember { mutableStateOf(ConversationMemory.isPersistenceEnabled(context)) }
    var voiceMemoryError by remember { mutableStateOf(ConversationMemory.persistenceError()) }
    val voiceActionJournal = remember { VoiceToolActionJournal(context) }
    val initialVoiceAction = remember { voiceActionJournal.latestResult() }
    var voiceActionEntry by remember { mutableStateOf(initialVoiceAction.getOrNull()) }
    var voiceActionError by remember { mutableStateOf(initialVoiceAction.exceptionOrNull()?.localizedMessage) }
    var showVoiceActionConfirm by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, voiceActionJournal) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val refreshed = voiceActionJournal.latestResult()
                voiceActionEntry = refreshed.getOrNull()
                voiceActionError = refreshed.exceptionOrNull()?.localizedMessage
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var screenOcrEnabled by remember { mutableStateOf(context.getSharedPreferences("shraddha_privacy", android.content.Context.MODE_PRIVATE).getBoolean("on_device_screen_ocr", false)) }

    var expandedSettingsGroup by remember { mutableStateOf<String?>(null) }
    var showHomeDesignDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(appState.assistantName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Text(
                text = "Settings",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        // Profile hero card
        NovaGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { navController.navigate("profile") },
            elevation = 6.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(colors.accentGlow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Avatar",
                        tint = colors.accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = appState.userName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Assistant: ${appState.assistantName} (${appState.personality.displayName})",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // Shraddha AI settings, grouped around its own design system and capabilities.
        SettingsGroupHeader(
            title = "Personality",
            expanded = expandedSettingsGroup == "Personality",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Personality") null else "Personality" }
        )
        if (expandedSettingsGroup == "Personality") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "Assistant Name",
                    subtitle = appState.assistantName,
                    icon = Icons.Default.Mic,
                    onClick = {
                        nameInput = appState.assistantName
                        showNameDialog = true
                    }
                )
                NovaSettingsRow(
                    title = "Personality",
                    subtitle = appState.personality.displayName,
                    icon = Icons.Default.Mood,
                    value = appState.personality.icon,
                    onClick = { navController.navigate("personality") }
                )
            }
        }

        }

        SettingsGroupHeader(
            title = "Voice & Language",
            expanded = expandedSettingsGroup == "Voice & Language",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Voice & Language") null else "Voice & Language" }
        )
        if (expandedSettingsGroup == "Voice & Language") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "Spoken Language",
                    subtitle = appState.language.displayName,
                    icon = Icons.Default.Language,
                    onClick = {
                        val nextLang = when (appState.language) {
                            LanguagePreference.AUTO -> LanguagePreference.HINDI
                            LanguagePreference.HINDI -> LanguagePreference.HINGLISH
                            LanguagePreference.HINGLISH -> LanguagePreference.ENGLISH
                            LanguagePreference.ENGLISH -> LanguagePreference.AUTO
                        }
                        appState.applyLanguage(context, nextLang)
                    }
                )
                NovaSettingsRow(
                    title = "${appState.assistantName} Voice Engine",
                    subtitle = "Custom Hindi-English voice • backend setup required",
                    icon = Icons.Default.RecordVoiceOver,
                    onClick = {
                        android.widget.Toast.makeText(
                            context,
                            "Configure a licensed Hindi-English voice model at SHRADDHA_TTS_URL on your secure backend.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                )
                NovaSettingsRow(
                    title = "Live Voice Backend",
                    subtitle = if (gatewayConfigured) "Secure HTTPS backend configured" else "Set up secure live voice access",
                    icon = Icons.Default.Security,
                    onClick = {
                        val current = VoiceGatewaySettings.get(context)
                        gatewayUrlInput = current.baseUrl
                        gatewayTokenInput = current.gatewayToken
                        gatewaySaveError = current.readError
                        showGatewayDialog = true
                    }
                )
            }
        }

        }

        SettingsGroupHeader(
            title = "Chats & Memory",
            expanded = expandedSettingsGroup == "Chats & Memory",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Chats & Memory") null else "Chats & Memory" }
        )
        if (expandedSettingsGroup == "Chats & Memory") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "Encrypted Conversation Memory",
                    subtitle = if (voiceMemoryEnabled) "Recent voice turns are saved encrypted on this phone" else "Off • voice turns stay in this session",
                    icon = Icons.Default.Speed,
                    switch = voiceMemoryEnabled,
                    onCheckedChange = { enabled ->
                        ConversationMemory.setPersistenceEnabled(context, enabled)
                            .onSuccess {
                                voiceMemoryEnabled = enabled
                                voiceMemoryError = null
                            }
                            .onFailure { error ->
                                voiceMemoryEnabled = ConversationMemory.isPersistenceEnabled(context)
                                voiceMemoryError = error.localizedMessage ?: "Could not update encrypted conversation memory."
                            }
                    }
                )
                voiceMemoryError?.let { error ->
                    Text(error, color = colors.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                NovaSettingsRow(
                    title = "Chat History",
                    subtitle = "Review saved conversations",
                    icon = Icons.Default.VolumeUp,
                    onClick = { navController.navigate("history") }
                )
            }
        }

        }

        if (voiceActionError != null || voiceActionEntry?.state in setOf("EXECUTING", "FAILED")) {
            NovaSectionHeader(title = "Voice Action Recovery")
            NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column {
                    Text(
                        text = voiceActionError ?: "A previous voice action may not have a confirmed result.",
                        color = if (voiceActionError != null) colors.error else colors.textPrimary,
                        fontSize = 13.sp
                    )
                    voiceActionEntry?.takeIf { it.state in setOf("EXECUTING", "FAILED") }?.let { entry ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tool: ${entry.tool} • State: ${entry.state}",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Check the target app yourself before confirming. This does not repeat or execute the action.",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        NovaButton(
                            text = "I checked the app — mark verified",
                            onClick = { showVoiceActionConfirm = true },
                            type = NovaButtonType.SECONDARY,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        SettingsGroupHeader(
            title = "Look & Feel",
            expanded = expandedSettingsGroup == "Look & Feel",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Look & Feel") null else "Look & Feel" }
        )
        if (expandedSettingsGroup == "Look & Feel") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "Home Screen Design",
                    subtitle = when (appState.homeDesign) {
                        "ROYAL_3D" -> "Royal 3D • layered premium dashboard"
                        "COSMIC_ORB" -> "Cosmic Orb • immersive orb-first layout"
                        "AI_ROBOT" -> "AI Robot • avatar and assistant controls"
                        "EXECUTIVE" -> "Executive • compact command dashboard"
                        else -> "Shraddha Signature • elegant glass layout"
                    },
                    icon = Icons.Default.ColorLens,
                    onClick = { showHomeDesignDialog = true }
                )
                NovaSettingsRow(
                    title = "Assistant Picture & Profile",
                    subtitle = "Change the ${appState.assistantName} avatar and profile details",
                    icon = Icons.Default.Person,
                    onClick = { navController.navigate("profile") }
                )
                NovaSettingsRow(
                    title = "3D Orb Style",
                    subtitle = appState.orbStyle,
                    icon = Icons.Default.ColorLens,
                    onClick = { navController.navigate("orb_style") }
                )
                NovaSettingsRow(
                    title = "Theme Mode",
                    subtitle = appState.themeMode,
                    icon = Icons.Default.DarkMode,
                    onClick = {
                        val nextMode = when (appState.themeMode) {
                            "DARK" -> "LIGHT"
                            "LIGHT" -> "SYSTEM"
                            else -> "DARK"
                        }
                        appState.applyThemeMode(context, nextMode)
                    }
                )
                NovaSettingsRow(
                    title = "Reduced Motion",
                    subtitle = "Minimize particle and parallax motion",
                    icon = Icons.Default.Animation,
                    switch = appState.reducedMotion,
                    onCheckedChange = { appState.applyReducedMotion(context, it) }
                )
            }
        }

        }

        SettingsGroupHeader(
            title = "Connections",
            expanded = expandedSettingsGroup == "Connections",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Connections") null else "Connections" }
        )
        if (expandedSettingsGroup == "Connections") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "API Keys",
                    subtitle = "Create and securely add keys for Gemini, OpenAI, Groq, Claude and Perplexity",
                    icon = Icons.Default.Key,
                    onClick = { navController.navigate("api_keys") }
                )
                NovaSettingsRow(
                    title = "AI Provider",
                    subtitle = "Tap to switch between supported providers",
                    icon = Icons.Default.Key,
                    value = appState.aiProvider,
                    onClick = {
                        val providers = listOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")
                        val currentIndex = providers.indexOf(appState.aiProvider).let { if (it < 0) 0 else it }
                        val nextProvider = providers[(currentIndex + 1) % providers.size]
                        appState.applyAiProvider(context, nextProvider)
                        android.widget.Toast.makeText(context, "AI provider: $nextProvider", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                NovaSettingsRow(
                    title = "Device Permissions",
                    subtitle = "Microphone, Contacts, Camera and Calls",
                    icon = Icons.Default.Security,
                    onClick = { navController.navigate("permissions") }
                )
                AccessibilitySettingsRow()
            }
        }

        }

        SettingsGroupHeader(
            title = "Main Brain • API Cost Control",
            expanded = expandedSettingsGroup == "Main Brain • API Cost Control",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Main Brain • API Cost Control") null else "Main Brain • API Cost Control" }
        )
        if (expandedSettingsGroup == "Main Brain • API Cost Control") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "Monthly API Spend Limit",
                    subtitle = if (monthlyBudgetInput.isBlank() || monthlyBudgetInput.toDoubleOrNull() == 0.0)
                        "Disabled • enter a USD limit to stop calls automatically"
                    else "Limit: USD $monthlyBudgetInput • spent this month: USD ${"%.4f".format(CouncilCostBudget.monthlySpendUsd(context))}",
                    icon = Icons.Default.Speed,
                    onClick = {
                        val current = CouncilCostBudget.settings(context)
                        monthlyBudgetInput = if (current.monthlyLimitUsd > 0) current.monthlyLimitUsd.toString() else ""
                        modelPricesInput = current.pricesJson
                        costBudgetError = null
                        showCostBudgetDialog = true
                    }
                )
            }
        }

        }

        SettingsGroupHeader(
            title = "Privacy & Security",
            expanded = expandedSettingsGroup == "Privacy & Security",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Privacy & Security") null else "Privacy & Security" }
        )
        if (expandedSettingsGroup == "Privacy & Security") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "On-device Screen Reading",
                    subtitle = if (screenOcrEnabled) "Hindi + English • processed on this phone" else "Off • optional screen text recognition",
                    icon = Icons.Default.Visibility,
                    switch = screenOcrEnabled,
                    onCheckedChange = {
                        screenOcrEnabled = it
                        context.getSharedPreferences("shraddha_privacy", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("on_device_screen_ocr", it).apply()
                    }
                )
                NovaSettingsRow(
                    title = "Privacy Center",
                    subtitle = "Review privacy information and controls",
                    icon = Icons.Default.Security,
                    onClick = { navController.navigate("privacy") }
                )
            }
        }

        }

        SettingsGroupHeader(
            title = "Broadcast & Voice Rooms",
            expanded = expandedSettingsGroup == "Broadcast & Voice Rooms",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "Broadcast & Voice Rooms") null else "Broadcast & Voice Rooms" }
        )
        if (expandedSettingsGroup == "Broadcast & Voice Rooms") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "Open Live Voice",
                    subtitle = "Start a voice session • mute or end it anytime",
                    icon = Icons.Default.VolumeUp,
                    onClick = { navController.navigate("voice") }
                )
            }
        }

        }

        SettingsGroupHeader(
            title = "About & Support",
            expanded = expandedSettingsGroup == "About & Support",
            onClick = { expandedSettingsGroup = if (expandedSettingsGroup == "About & Support") null else "About & Support" }
        )
        if (expandedSettingsGroup == "About & Support") {
        NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                NovaSettingsRow(
                    title = "About ${appState.assistantName}",
                    subtitle = "Version, developer contact and copyright",
                    icon = Icons.Default.Key,
                    onClick = { navController.navigate("about") }
                )
            }
        }
        }

    }

    if (showHomeDesignDialog) {
        val designs = listOf(
            "SHRADDHA_SIGNATURE" to ("Shraddha Signature" to "Elegant layered glass cards and spacious hero"),
            "ROYAL_3D" to ("Royal 3D" to "Deep 3D panels, raised controls and premium depth"),
            "COSMIC_ORB" to ("Cosmic Orb" to "Large animated orb with immersive assistant focus"),
            "AI_ROBOT" to ("AI Robot" to "Avatar-led assistant layout with quick control dock"),
            "EXECUTIVE" to ("Executive" to "Compact command dashboard with grouped tools")
        )
        AlertDialog(
            onDismissRequest = { showHomeDesignDialog = false },
            title = { Text("Choose Home Screen Design", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("These options change the screen layout and component arrangement—not just its colors.", color = colors.textSecondary, fontSize = 12.sp)
                    designs.forEach { (id, details) ->
                        NovaGlassCard(
                            modifier = Modifier.fillMaxWidth().clickable {
                                appState.applyHomeDesign(context, id)
                                showHomeDesignDialog = false
                                android.widget.Toast.makeText(context, "${details.first} design applied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(details.first, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(details.second, color = colors.textSecondary, fontSize = 11.sp)
                                }
                                if (appState.homeDesign == id) Text("✓ ACTIVE", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { NovaButton(text = "Done", onClick = { showHomeDesignDialog = false }) }
        )
    }

    // Secure Voice Gateway Dialog
    if (showGatewayDialog) {
        AlertDialog(
            onDismissRequest = { showGatewayDialog = false },
            title = { Text(text = "Secure Gemini Live Backend", color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        text = "Voice sessions request a one-use, short-lived Gemini token from your HTTPS backend. Keep GATEWAY_TOKEN private. The permanent Gemini API key stays on the server.",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaTextField(
                        value = gatewayUrlInput,
                        onValueChange = { gatewayUrlInput = it },
                        placeholder = VoiceGatewaySettings.DEFAULT_BASE_URL
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gatewayTokenInput,
                        onValueChange = { gatewayTokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Backend GATEWAY_TOKEN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (gatewaySaveError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(gatewaySaveError!!, color = colors.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                NovaButton(
                    text = "Save Secure Backend",
                    onClick = {
                        val result = VoiceGatewaySettings.save(context, gatewayUrlInput, gatewayTokenInput)
                        result.onSuccess {
                            gatewayConfigured = true
                            gatewaySaveError = null
                            showGatewayDialog = false
                        }.onFailure {
                            gatewaySaveError = it.localizedMessage ?: "Could not save backend settings"
                        }
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Clear",
                    onClick = {
                        runCatching { VoiceGatewaySettings.clear(context) }
                            .onSuccess {
                                gatewayUrlInput = ""
                                gatewayTokenInput = ""
                                gatewayConfigured = false
                                gatewaySaveError = null
                                showGatewayDialog = false
                            }
                            .onFailure {
                                gatewaySaveError = it.localizedMessage ?: "Could not clear saved backend settings"
                            }
                    },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }

    if (showCostBudgetDialog) {
        AlertDialog(
            onDismissRequest = { showCostBudgetDialog = false },
            title = { Text("Main Brain API Cost Budget", color = colors.textPrimary) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Set a monthly USD ceiling. The council records provider-reported tokens after each response and stops before starting another provider call when the estimated spend crosses the limit. A zero/blank limit disables enforcement.",
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = monthlyBudgetInput,
                        onValueChange = { monthlyBudgetInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Monthly limit (USD)") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Model prices (JSON)", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Use exact provider/model names and USD per 1 million tokens. Example: [{\"provider\":\"OPENAI\",\"model\":\"gpt-4.1-mini\",\"inputUsdPerMillionTokens\":0.4,\"outputUsdPerMillionTokens\":1.6}]",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = modelPricesInput,
                        onValueChange = { modelPricesInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Price table JSON") },
                        minLines = 4,
                        maxLines = 8
                    )
                    if (costBudgetError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(costBudgetError!!, color = colors.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                NovaButton(text = "Save Budget", onClick = {
                    val limit = monthlyBudgetInput.trim().ifBlank { "0" }.toDoubleOrNull()
                    if (limit == null) {
                        costBudgetError = "Enter a valid monthly USD amount."
                    } else {
                        runCatching { CouncilCostBudget.saveSettings(context, limit, modelPricesInput) }
                            .onSuccess {
                                costBudgetError = null
                                showCostBudgetDialog = false
                            }
                            .onFailure { costBudgetError = it.localizedMessage ?: "Could not save API cost settings." }
                    }
                })
            },
            dismissButton = {
                NovaButton(text = "Close", onClick = { showCostBudgetDialog = false }, type = NovaButtonType.TEXT)
            },
            containerColor = colors.surfaceRaised
        )
    }

    // Assistant Name Dialog
    if (showVoiceActionConfirm) {
        AlertDialog(
            onDismissRequest = { showVoiceActionConfirm = false },
            title = { Text("Confirm voice action recovery") },
            text = { Text("Have you personally checked the target app and confirmed whether the previous action completed? This only records your confirmation; it will not run the action again.") },
            confirmButton = {
                NovaButton(text = "Yes, I checked", onClick = {
                    showVoiceActionConfirm = false
                    val entry = voiceActionEntry
                    if (entry != null && voiceActionJournal.markManuallyVerified(entry.taskId)) {
                        val refreshed = voiceActionJournal.latestResult()
                        voiceActionEntry = refreshed.getOrNull()
                        voiceActionError = refreshed.exceptionOrNull()?.localizedMessage
                    } else {
                        voiceActionError = "Could not save manual verification. The previous action remains unresolved."
                    }
                })
            },
            dismissButton = {
                NovaButton(text = "Cancel", onClick = { showVoiceActionConfirm = false }, type = NovaButtonType.TEXT)
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(text = "Rename Assistant", color = colors.textPrimary) },
            text = {
                NovaTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = "अपना पसंदीदा नाम लिखें"
                )
            },
            confirmButton = {
                NovaButton(
                    text = "Save",
                    onClick = {
                        appState.applyAssistantName(context, nameInput)
                        showNameDialog = false
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { showNameDialog = false },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}

@Composable
private fun SettingsGroupHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    val colors = LocalNovaColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(if (expanded) "−" else "+", color = colors.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
