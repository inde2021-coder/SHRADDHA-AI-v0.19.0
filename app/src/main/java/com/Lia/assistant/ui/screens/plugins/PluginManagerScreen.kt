package com.Lia.assistant.ui.screens.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import `in`.shraddha.ai.plugins.DeclarativePluginRuntime
import `in`.shraddha.ai.plugins.PluginActionPolicy
import `in`.shraddha.ai.plugins.PluginGatewayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun PluginManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    var rawManifest by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf<JSONObject?>(null) }
    var plugin by remember { mutableStateOf<JSONObject?>(null) }
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Import a signed JSON manifest. Unsigned or executable plugins are rejected.") }

    fun verify() {
        if (busy) return
        val parsed = runCatching { JSONObject(rawManifest) }.getOrElse {
            status = "Manifest JSON is invalid: ${it.message}"
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PluginGatewayClient.verify(context, parsed) } }
            result.onSuccess { response ->
                manifest = parsed
                plugin = response.optJSONObject("plugin")
                status = "Signature verified. Only the listed local actions can be approved; no plugin code is downloaded."
            }.onFailure { status = it.message ?: "Plugin verification failed." }
            busy = false
        }
    }

    fun execute(index: Int) {
        val signedManifest = manifest ?: return
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val authorization = PluginGatewayClient.authorize(context, signedManifest, index)
                    val action = PluginActionPolicy.parse(authorization)
                    DeclarativePluginRuntime.execute(context, action, userConfirmed = true).getOrThrow()
                }
            }
            status = result.fold({ it }, { "Action not executed: ${it.message ?: "unknown error"}" })
            busy = false
            pendingIndex = null
        }
    }

    Column(
        Modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(title = "PLUGIN VAULT", navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
        })
        NovaGlassCard {
            Text("SIGNED DECLARATIVE PLUGINS", color = colors.accent)
            Text("Only Ed25519-trusted manifests and four built-in actions are supported: encrypted local note, Android notification, clipboard copy and safe HTTPS browser hand-off. Scripts, shell commands, remote URLs, DEX/JAR and arbitrary permissions are never executed.", color = colors.textSecondary)
            OutlinedTextField(
                value = rawManifest, onValueChange = { rawManifest = it.take(32_000) },
                modifier = Modifier.fillMaxWidth(), minLines = 8, maxLines = 16,
                label = { Text("Signed plugin manifest JSON") }
            )
            NovaButton(text = if (busy) "Working…" else "VERIFY SIGNATURE", onClick = { verify() }, enabled = !busy && rawManifest.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
        plugin?.let { verified ->
            NovaGlassCard {
                Text(verified.optString("name"), color = colors.textPrimary)
                Text("ID: ${verified.optString("id")} • Version: ${verified.optString("version")}", color = colors.textSecondary)
                val actions = verified.optJSONArray("actions")
                if (actions != null) {
                    for (i in 0 until actions.length()) {
                        val action = actions.optJSONObject(i) ?: continue
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text("${i + 1}. ${action.optString("type")}", color = colors.textPrimary)
                            Text(action.optString("title") + " — " + action.optString("body") + if (action.has("url")) " — " + action.optString("url") else "", color = colors.textSecondary)
                            NovaButton(
                                text = "Review & run this action",
                                onClick = { pendingIndex = i },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                type = NovaButtonType.SECONDARY
                            )
                        }
                    }
                }
            }
        }
        NovaGlassCard { Text(status, color = colors.textSecondary) }
    }

    val index = pendingIndex
    if (index != null) {
        val action = manifest?.optJSONArray("actions")?.optJSONObject(index)
        AlertDialog(
            onDismissRequest = { if (!busy) pendingIndex = null },
            title = { Text("Approve plugin action?") },
            text = { Text(action?.let { it.optString("type") + "\\n" + it.optString("title") + "\\n" + it.optString("body") + if (it.has("url")) "\\n" + it.optString("url") else "" } ?: "Action unavailable") },
            confirmButton = { TextButton(onClick = { execute(index) }, enabled = !busy) { Text("Approve & run") } },
            dismissButton = { TextButton(onClick = { pendingIndex = null }, enabled = !busy) { Text("Cancel") } }
        )
    }
}
