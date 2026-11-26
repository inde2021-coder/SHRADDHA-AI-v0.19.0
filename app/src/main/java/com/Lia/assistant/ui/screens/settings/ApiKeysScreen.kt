package com.Lia.assistant.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.ProviderKeyStore
import com.Lia.assistant.data.PasswordProtectedApiKeyVault
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.GeminiTextClient
import com.Lia.assistant.voice.MultiProviderChatClient
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky

private data class ProviderKeyOption(
    val id: String,
    val title: String,
    val description: String,
    val createUrl: String
)

private val apiKeyProviders = listOf(
    ProviderKeyOption("GEMINI", "Google Gemini", "Google AI Studio se API key banayein.", "https://aistudio.google.com/app/apikey"),
    ProviderKeyOption("OPENAI", "OpenAI / ChatGPT", "OpenAI Platform par secret key banayein.", "https://platform.openai.com/api-keys"),
    ProviderKeyOption("GROQ", "Groq", "Groq Console se API key banayein.", "https://console.groq.com/keys"),
    ProviderKeyOption("CLAUDE", "Anthropic Claude", "Anthropic Console par API key banayein.", "https://console.anthropic.com/settings/keys"),
    ProviderKeyOption("PERPLEXITY", "Perplexity", "Perplexity API settings se key banayein.", "https://www.perplexity.ai/settings/api")
)

private fun readProviderKey(context: android.content.Context, provider: String): Boolean =
    if (provider == "GEMINI") ApiKeyStore.hasKey(context) else ProviderKeyStore.has(context, provider)

private fun maskedProviderKey(context: android.content.Context, provider: String): String? {
    val saved = if (provider == "GEMINI") ApiKeyStore.getKey(context) else ProviderKeyStore.get(context, provider)
    return saved.takeIf { it.isNotBlank() }?.let { "Saved key: ••••••••${it.takeLast(4)}" }
}

private fun saveProviderKey(context: android.content.Context, provider: String, value: String) {
    if (provider == "GEMINI") ApiKeyStore.saveKey(context, value)
    else ProviderKeyStore.save(context, provider, value)
}

private fun clearProviderKey(context: android.content.Context, provider: String) {
    saveProviderKey(context, provider, "")
}


private fun testSavedProviderKey(context: android.content.Context, provider: String): String {
    val startedAt = System.currentTimeMillis()
    val instruction = "You are a provider connectivity check. Reply with exactly the word OK."
    val prompt = "Reply with exactly the word OK."
    val history = listOf(ChatTurn(isUser = true, text = prompt))
    val result = if (provider == "GEMINI") {
        val key = ApiKeyStore.getKey(context)
        if (key.isBlank()) return "GEMINI API key is missing."
        GeminiTextClient.reply(key, instruction, history)
    } else {
        MultiProviderChatClient.reply(context, provider, instruction, history)
    }
    return when (result) {
        is ChatReplyResult.Success -> {
            if (result.text.isBlank()) error("$provider returned an empty connection-test reply.")
            val model = result.tokenUsage?.model ?: "not reported by provider response"
            "HTTP=2xx; model=$model; response=received; durationMs=${System.currentTimeMillis() - startedAt}"
        }
        is ChatReplyResult.Error -> error(result.message)
    }
}

private fun safeDiagnosticText(value: String, secrets: List<String> = emptyList()): String {
    var sanitized = value
    secrets.filter { it.isNotBlank() }.forEach { secret -> sanitized = sanitized.replace(secret, "[REDACTED_SECRET]") }
    return sanitized
        .replace(Regex("(?i)(authorization|api[-_ ]?key|gateway[-_ ]?token|x-goog-api-key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        .replace(Regex("(?i)\\b(AIza[0-9A-Za-z_-]{12,}|sk-[0-9A-Za-z_-]{12,}|gsk_[0-9A-Za-z_-]{12,}|pplx-[0-9A-Za-z_-]{12,}|sk-ant-[0-9A-Za-z_-]{12,})\\b"), "[REDACTED_KEY]")
        .take(700)
}

private fun buildApiDiagnosticReport(context: Context, configured: Map<String, Boolean>, testStatus: Map<String, String>): String {
    val packageInfo = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val gateway = VoiceGatewaySettings.get(context)
    val gatewayHost = runCatching { java.net.URI(gateway.baseUrl).host }.getOrNull().orEmpty()
    val savedSecrets = apiKeyProviders.mapNotNull { provider ->
        runCatching { if (provider.id == "GEMINI") ApiKeyStore.getKey(context) else ProviderKeyStore.get(context, provider.id) }.getOrNull()
    } + listOf(gateway.gatewayToken)
    return buildString {
        appendLine("Shraddha AI — API Diagnostic Report")
        appendLine("Generated: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date()))
        appendLine("App version: " + (packageInfo?.versionName ?: "unknown") + " (" + (packageInfo?.versionCode ?: 0) + ")")
        appendLine("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
        appendLine()
        appendLine("Provider checks (keys are never included):")
        apiKeyProviders.forEach { provider ->
            appendLine("- " + provider.id + ": keySaved=" + (configured[provider.id] == true) + "; lastTest=" + safeDiagnosticText(testStatus[provider.id].orEmpty().ifBlank { "Not tested in this session" }, savedSecrets))
        }
        appendLine()
        appendLine("Secure backend:")
        appendLine("- configured=" + (gateway.baseUrl.isNotBlank() && gateway.gatewayToken.isNotBlank() && gateway.readError == null))
        appendLine("- host=" + gatewayHost.ifBlank { "not set" })
        appendLine("- settingsReadError=" + (gateway.readError?.let { safeDiagnosticText(it, savedSecrets) } ?: "none"))
        appendLine()
        appendLine("Privacy: API keys, gateway token, request prompts and response content are excluded.")
    }
}
@Composable
fun ApiKeysScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var vaultAccess by remember { mutableStateOf(PasswordProtectedApiKeyVault.isConfigured(context) && PasswordProtectedApiKeyVault.isUnlocked()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vaultAccess = PasswordProtectedApiKeyVault.isConfigured(context) && PasswordProtectedApiKeyVault.isUnlocked()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!vaultAccess) {
        Column(modifier = modifier.fillMaxSize().nightSky().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
                Text("Unlock AI API Keys", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            ApiKeyVaultPanel(context) { vaultAccess = it }
        }
        return
    }
    val keyReadWarnings = remember { mutableStateMapOf<String, String>() }
    val configured = remember {
        mutableStateMapOf<String, Boolean>().apply {
            apiKeyProviders.forEach { provider ->
                val result = runCatching { readProviderKey(context, provider.id) }
                put(provider.id, result.getOrDefault(false))
                result.exceptionOrNull()?.let {
                    keyReadWarnings[provider.id] = "Saved key could not be decrypted. Re-enter the key if this device's Keystore was reset."
                }
            }
        }
    }
    val savedKeyHints = remember {
        mutableStateMapOf<String, String>().apply {
            apiKeyProviders.forEach { provider ->
                runCatching { maskedProviderKey(context, provider.id) }
                    .getOrNull()?.let { put(provider.id, it) }
            }
        }
    }
    val inputs = remember { mutableStateMapOf<String, String>() }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }
    val testStatus = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    var diagnosticReport by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Column {
                Text("AI API Keys", color = colors.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Create a key on provider website, then add it securely here.", color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        ApiKeyVaultPanel(context) { vaultAccess = it }
        Spacer(Modifier.height(12.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, elevation = 5.dp) {
            Text(
                "API keys are saved in encrypted phone storage. Test Connection checks this provider key directly. Live microphone voice uses the separate Secure Backend settings below; a provider key alone does not configure the voice gateway. Never share your key or add it to screenshots.",
                color = colors.textSecondary,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        apiKeyProviders.forEach { provider ->
            NovaGlassCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                cornerRadius = 20.dp,
                elevation = 6.dp
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = colors.accent)
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(provider.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(provider.description, color = colors.textSecondary, fontSize = 12.sp)
                            keyReadWarnings[provider.id]?.let { warning ->
                                Text(warning, color = colors.error, fontSize = 10.sp)
                            }
                        }
                        if (configured[provider.id] == true) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "API key saved", tint = colors.accent)
                        }
                    }
                    OutlinedTextField(
                        value = inputs[provider.id].orEmpty(),
                        onValueChange = { inputs[provider.id] = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (configured[provider.id] == true) "Enter new key to replace saved key" else "Paste API key") },
                        placeholder = { Text("API key") },
                        visualTransformation = if (revealed[provider.id] == true) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealed[provider.id] = revealed[provider.id] != true }) {
                                Icon(
                                    if (revealed[provider.id] == true) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (revealed[provider.id] == true) "Hide key" else "Show key"
                                )
                            }
                        }
                    )
                    Text(
                        text = savedKeyHints[provider.id]
                            ?: if (configured[provider.id] == true) "Key saved securely on this phone. Enter a new key to replace it."
                            else "Key is hidden while typing. Tap the eye icon to view it.",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NovaButton(
                            text = "Save Key",
                            onClick = {
                                val key = inputs[provider.id].orEmpty().trim()
                                if (key.isBlank()) {
                                    Toast.makeText(context, "Pehle ${provider.title} ki API key paste karein.", Toast.LENGTH_SHORT).show()
                                } else {
                                    runCatching { saveProviderKey(context, provider.id, key) }
                                        .onSuccess {
                                            configured[provider.id] = true
                                            savedKeyHints[provider.id] = "Saved key: ••••••••${key.takeLast(4)}"
                                            keyReadWarnings.remove(provider.id)
                                            inputs[provider.id] = ""
                                            revealed[provider.id] = false
                                            Toast.makeText(context, "${provider.title} API key securely saved.", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(context, "Key save nahi hui: ${it.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                        }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        NovaButton(
                            text = "Remove",
                            type = NovaButtonType.SECONDARY,
                            onClick = {
                                runCatching { clearProviderKey(context, provider.id) }
                                    .onSuccess {
                                        configured[provider.id] = false
                                        savedKeyHints.remove(provider.id)
                                        keyReadWarnings.remove(provider.id)
                                        inputs[provider.id] = ""
                                        Toast.makeText(context, "${provider.title} key removed.", Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure {
                                        Toast.makeText(context, "Key remove nahi hui.", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        )
                    }
                    NovaButton(
                        text = if (testing[provider.id] == true) "Testing…" else "Test Connection",
                        type = NovaButtonType.SECONDARY,
                        onClick = {
                            testing[provider.id] = true
                            testStatus[provider.id] = "Sending one short provider request; usage charges may apply."
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        testSavedProviderKey(context, provider.id)
                                    }
                                }
                                testStatus[provider.id] = result.fold(
                                    onSuccess = { details -> "Connection successful: $details" },
                                    onFailure = { error -> "Connection failed: ${error.message ?: "Unknown provider error"}" }
                                )
                                testing[provider.id] = false
                            }
                        },
                        enabled = configured[provider.id] == true && testing[provider.id] != true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    testStatus[provider.id]?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            message,
                            color = when { message.startsWith("Connection successful") -> colors.success; message.startsWith("Connection failed") -> colors.error; else -> colors.textSecondary },
                            fontSize = 10.sp
                        )
                    }
                    NovaButton(
                        text = "Create API Key",
                        icon = Icons.Default.OpenInNew,
                        type = NovaButtonType.SECONDARY,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.createUrl)))
                            }.onFailure {
                                Toast.makeText(context, "Provider website open nahi ho saki.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (configured[provider.id] == true) "Status: Key saved securely on this device • full key stays hidden" else "Status: API key not added",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, elevation = 5.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("API Diagnostic Report", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Share a safe report with support. It includes provider test results, app/device details and backend configuration status — never API keys or gateway tokens.", color = colors.textSecondary, fontSize = 12.sp)
                NovaButton(
                    text = "Generate & Share Diagnostic Report",
                    type = NovaButtonType.SECONDARY,
                    onClick = {
                        diagnosticReport = buildApiDiagnosticReport(context, configured.toMap(), testStatus.toMap())
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Shraddha AI API Diagnostic Report")
                                putExtra(Intent.EXTRA_TEXT, diagnosticReport)
                            }
                            context.startActivity(Intent.createChooser(send, "Share diagnostic report"))
                        }.onFailure {
                            Toast.makeText(context, "Report generated, but share sheet could not open.", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (diagnosticReport.isNotBlank()) Text("Report generated. Review it before sharing.", color = colors.success, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}
