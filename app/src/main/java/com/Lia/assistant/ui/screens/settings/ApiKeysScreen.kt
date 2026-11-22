package com.Lia.assistant.ui.screens.settings

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import `in`.shraddha.ai.future.MainBrainProviderGateway
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

private fun saveProviderKey(context: android.content.Context, provider: String, value: String) {
    if (provider == "GEMINI") ApiKeyStore.saveKey(context, value)
    else ProviderKeyStore.save(context, provider, value)
}

private fun clearProviderKey(context: android.content.Context, provider: String) {
    saveProviderKey(context, provider, "")
}

@Composable
fun ApiKeysScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val configured = remember {
        mutableStateMapOf<String, Boolean>().apply {
            apiKeyProviders.forEach { put(it.id, readProviderKey(context, it.id)) }
        }
    }
    val inputs = remember { mutableStateMapOf<String, String>() }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }
    val testStatus = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

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
        NovaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, elevation = 5.dp) {
            Text(
                "API keys are saved in the phone's encrypted Android Keystore-backed storage. Never share your key or add it to screenshots.",
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
                                        MainBrainProviderGateway.complete(
                                            context,
                                            provider.id,
                                            "You are a provider connectivity check. Reply with exactly the word OK.",
                                            "Reply with exactly the word OK."
                                        )
                                    }
                                }
                                testStatus[provider.id] = result.fold(
                                    onSuccess = { reply ->
                                        if (reply.contains("OK", ignoreCase = true)) "Connection successful: provider returned a response."
                                        else "Provider responded, but the test reply was unexpected."
                                    },
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
                            color = if (message.startsWith("Connection successful")) colors.accent else colors.textSecondary,
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
                        if (configured[provider.id] == true) "Status: Key saved on this device" else "Status: API key not added",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}
