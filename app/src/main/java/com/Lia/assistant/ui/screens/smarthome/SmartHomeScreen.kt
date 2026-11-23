package com.Lia.assistant.ui.screens.smarthome

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import `in`.shraddha.ai.brain.HomeAssistantGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SmartHomeScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    val gateway = remember { HomeAssistantGateway(context.applicationContext) }
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var entities by remember { mutableStateOf(emptyList<HomeAssistantGateway.Entity>()) }
    var selected by remember { mutableStateOf<HomeAssistantGateway.Entity?>(null) }
    var pendingService by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("24") }
    var status by remember { mutableStateOf("Connect a Home Assistant HTTPS endpoint. Credentials are stored in the encrypted feature vault.") }
    var busy by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { gateway.listEntities() }
            result.onSuccess { entities = it; status = "Loaded ${it.size} supported devices from Home Assistant." }
                .onFailure { status = it.localizedMessage ?: "Could not load smart-home devices." }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NovaTopBar(title = "SMART HOME", navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
        })
        NovaGlassCard {
            Text("HOME ASSISTANT • SECURE CONNECTOR", color = colors.accent)
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it.take(500) }, modifier = Modifier.fillMaxWidth(), label = { Text("HTTPS server URL") }, singleLine = true)
            OutlinedTextField(value = token, onValueChange = { token = it.take(4096) }, modifier = Modifier.fillMaxWidth(), label = { Text("Long-lived access token") }, singleLine = true)
            NovaButton(text = "SAVE ENCRYPTED CONNECTION", onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { gateway.saveConfig(baseUrl, token) }
                    result.onSuccess { token = ""; status = "Encrypted Home Assistant connection saved."; refresh() }
                        .onFailure { status = it.localizedMessage ?: "Connection settings were not saved." }
                    busy = false
                }
            }, enabled = !busy && baseUrl.isNotBlank() && token.length >= 16, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "REFRESH DEVICES", onClick = { refresh() }, enabled = !busy, type = NovaButtonType.SECONDARY, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "FORGET CONNECTION", onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { gateway.clearConfig() }
                    result.onSuccess { entities = emptyList(); selected = null; status = "Encrypted smart-home credentials cleared." }
                        .onFailure { status = it.localizedMessage ?: "Could not clear credentials." }
                }
            }, type = NovaButtonType.TEXT)
        }
        NovaGlassCard {
            Text("SUPPORTED DEVICES", color = colors.accent)
            if (entities.isEmpty()) Text("No devices loaded. Connect and refresh.", color = colors.textSecondary)
            entities.forEach { entity ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(entity.name, color = colors.textPrimary)
                        Text("${entity.entityId} • ${entity.state}", color = colors.textSecondary)
                    }
                    NovaButton(text = if (selected?.entityId == entity.entityId) "Selected" else "Control", onClick = { selected = entity }, type = NovaButtonType.TEXT)
                }
            }
        }
        selected?.let { entity ->
            NovaGlassCard {
                Text("CONTROL • ${entity.name}", color = colors.accent)
                when (entity.domain) {
                    "light", "switch", "fan" -> listOf("turn_on", "turn_off", "toggle").forEach { service ->
                        NovaButton(text = service.replace('_', ' ').uppercase(), onClick = { pendingService = service; showConfirm = true }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
                    }
                    "cover" -> listOf("open_cover", "close_cover", "stop_cover").forEach { service ->
                        NovaButton(text = service.replace('_', ' ').uppercase(), onClick = { pendingService = service; showConfirm = true }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
                    }
                    "climate" -> {
                        OutlinedTextField(value = temperature, onValueChange = { temperature = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) }, label = { Text("Temperature °C (16–30)") })
                        NovaButton(text = "SET TEMPERATURE", onClick = { pendingService = "set_temperature"; showConfirm = true }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        NovaGlassCard { Text(status, color = colors.textSecondary) }
        Text("This build uses the HTTPS Home Assistant REST adapter with explicit per-action confirmation and a service allowlist. MQTT, Matter commissioning and vendor-specific local radios need their respective broker/hardware adapters; they are not falsely shown as connected.", color = colors.textSecondary)
    }
    if (showConfirm) {
        AlertDialog(onDismissRequest = { showConfirm = false }, title = { Text("Confirm smart-home action") },
            text = { Text("Run $pendingService on ${selected?.entityId}? This sends a real command to your home.") },
            confirmButton = { NovaButton(text = "CONFIRM", onClick = {
                val target = selected
                showConfirm = false
                if (target != null) {
                    busy = true
                    scope.launch {
                        val temp = if (pendingService == "set_temperature") temperature.toDoubleOrNull() else null
                        val result = withContext(Dispatchers.IO) { gateway.callService(true, target.entityId, pendingService, temp) }
                        result.onSuccess { status = it; refresh() }.onFailure { status = it.localizedMessage ?: "Smart-home command failed." }
                        busy = false
                    }
                }
            }) },
            dismissButton = { NovaButton(text = "CANCEL", onClick = { showConfirm = false }, type = NovaButtonType.TEXT) })
    }
}
