package com.Lia.assistant.ui.screens.phone

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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.EdesyCallAgentClient
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.TelephonyGatewayStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PhoneCallAgentScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val assistantName = NovaPreferences.getString(context, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.FULL_NAME).trim().ifBlank { AssistantBrand.FULL_NAME }
    val scope = rememberCoroutineScope()
    val savedGateway = remember { TelephonyGatewayStore.get(context) }
    var backendUrl by remember { mutableStateOf(savedGateway.baseUrl) }
    var gatewayToken by remember { mutableStateOf(savedGateway.token) }
    var loading by remember { mutableStateOf(false) }
    var resultText by remember {
        mutableStateOf(savedGateway.readError ?: "Backend readiness has not been checked yet.")
    }
    var callRows by remember { mutableStateOf(emptyList<String>()) }

    fun runRequest(request: suspend () -> JSONObject, onSuccess: (JSONObject) -> Unit) {
        if (loading) return
        scope.launch {
            loading = true
            try {
                onSuccess(request())
            } catch (error: Exception) {
                resultText = error.message ?: "Phone Call Agent request failed."
            } finally {
                loading = false
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
            title = "PHONE CALL AGENT",
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
            }
        )

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = colors.accent)
                    Text("SIM no-answer call assistant", color = colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                }
                Text(
                    "Caller पहले आपके Jio SIM पर ring करेगा. जवाब न देने पर carrier call को Edesy DID पर forward करेगा, जहाँ $assistantName बातचीत संभालेगी. यह Android द्वारा SIM audio intercept नहीं करता.",
                    color = colors.textSecondary, fontSize = 13.sp
                )
                Text(
                    "पहले Edesy DID और inbound Media Stream configure करें. Two-way call test सफल होने से पहले Jio forwarding चालू न करें.",
                    color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
        }

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Secure backend connection", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                NovaTextField(value = backendUrl, onValueChange = { backendUrl = it },
                    modifier = Modifier.fillMaxWidth(), placeholder = "https://your-backend.example.com")
                NovaTextField(value = gatewayToken, onValueChange = { gatewayToken = it },
                    modifier = Modifier.fillMaxWidth(), placeholder = "Backend GATEWAY_TOKEN", secret = true)
                Text(
                    "URL और gateway token इस फोन पर Android Keystore-backed encrypted storage में रखे जाते हैं.",
                    color = colors.textSecondary, fontSize = 12.sp
                )
                NovaButton(text = "Save Secure Connection", onClick = {
                    try {
                        TelephonyGatewayStore.save(context, backendUrl, gatewayToken)
                        resultText = "Backend connection saved securely."
                    } catch (error: Exception) {
                        resultText = error.message ?: "Could not save backend connection."
                    }
                }, modifier = Modifier.fillMaxWidth())
                NovaButton(text = "Clear Saved Connection", onClick = {
                    try {
                        TelephonyGatewayStore.clear(context)
                        backendUrl = ""
                        gatewayToken = ""
                        callRows = emptyList()
                        resultText = "Saved backend URL and gateway token were cleared."
                    } catch (error: Exception) {
                        resultText = error.message ?: "Could not clear saved backend connection."
                    }
                }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
            }
        }

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Readiness & live audio preflight", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(resultText, color = colors.textSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaButton(text = "Check Readiness", onClick = {
                        runRequest({ EdesyCallAgentClient.status(backendUrl, gatewayToken) }) { body ->
                            val missing = body.optJSONArray("missing")
                            val missingText = (0 until (missing?.length() ?: 0)).mapNotNull { missing?.optString(it) }
                            resultText = buildString {
                                append("Provider configured: ").append(body.optBoolean("configured"))
                                append("\nPreflight ready: ").append(body.optBoolean("preflightReady"))
                                append("\nAI name: ").append(assistantName)
                                val history = body.optJSONObject("callHistory")
                                append("\nEncrypted durable call history: ").append(if (history?.optBoolean("durable") == true) "READY" else "not configured")
                                val historyMissing = history?.optJSONArray("missing")
                                if (historyMissing != null && historyMissing.length() > 0) append("\nCall-history secrets missing: ").append((0 until historyMissing.length()).joinToString(", ") { historyMissing.optString(it) })
                                val historyError = history?.optString("error").orEmpty()
                                if (historyError.isNotBlank()) append("\nCall-history error: ").append(historyError)
                                if (missingText.isNotEmpty()) append("\nMissing provider config: ").append(missingText.joinToString(", "))
                                append("\n").append(body.optString("note"))
                            }
                        }
                    }, modifier = Modifier.weight(1f), enabled = !loading)
                    NovaButton(text = "Run Preflight", onClick = {
                        runRequest({ EdesyCallAgentClient.preflight(backendUrl, gatewayToken) }) { body ->
                            val steps = body.optJSONArray("steps")
                            val details = (0 until (steps?.length() ?: 0)).mapNotNull { index ->
                                val step = steps?.optJSONObject(index) ?: return@mapNotNull null
                                "${step.optString("step")}: ${if (step.optBoolean("ok")) "PASS" else "FAIL"} — ${step.optString("detail")}"
                            }
                            resultText = "Edesy audio preflight: ${if (body.optBoolean("ok")) "PASS" else "NOT READY"}\n" + details.joinToString("\n")
                        }
                    }, modifier = Modifier.weight(1f), enabled = !loading)
                }
                NovaButton(text = "Refresh Call History", onClick = {
                    runRequest({ EdesyCallAgentClient.calls(backendUrl, gatewayToken) }) { body ->
                        val calls = body.optJSONArray("calls")
                        callRows = (0 until (calls?.length() ?: 0)).mapNotNull { index ->
                            val item = calls?.optJSONObject(index) ?: return@mapNotNull null
                            "${item.optString("startedAt", "Time unavailable")} • ${item.optString("caller", "Unknown caller")} • ${item.optString("status", "unknown")}"
                        }
                        resultText = "Loaded ${callRows.size} recent call record(s). Storage: ${if (body.optBoolean("durable")) "encrypted Firestore" else "temporary backend memory"}." + if (body.optString("warning").isNotBlank()) "\\n" + body.optString("warning") else ""
                    }
                }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY, enabled = !loading)
                if (loading) Text("Connecting securely…", color = colors.accent, fontSize = 12.sp)
            }
        }

        if (callRows.isNotEmpty()) {
            NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent inbound calls", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    callRows.forEach { Text(it, color = colors.textSecondary, fontSize = 12.sp) }
                }
            }
        }

        Text(
            "Safety: forwarding is controlled by Jio, not this app. Keep unconditional forwarding off. First test the Edesy number directly, including two-way audio and hang-up, then ask Jio to enable no-answer forwarding with a ring delay.",
            color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}
