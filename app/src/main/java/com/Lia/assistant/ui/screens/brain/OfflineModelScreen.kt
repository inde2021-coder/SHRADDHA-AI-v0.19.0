package com.Lia.assistant.ui.screens.brain

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import `in`.shraddha.ai.brain.LocalOfflineLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MODEL_PREFS = "shraddha_offline_model"
private const val MODEL_PATH_KEY = "active_model_path"

@Composable
fun OfflineModelScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE) }
    var modelPath by remember { mutableStateOf(prefs.getString(MODEL_PATH_KEY, "").orEmpty()) }
    var status by remember { mutableStateOf("Import a compatible LiteRT-LM .litertlm model to run text inference fully on this phone.") }
    var report by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("नमस्ते, अपना परिचय हिंदी और English में दो।") }
    var answer by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { LocalOfflineLlm.close() } }

    fun importModel(uri: Uri) {
        working = true
        status = "Copying model into private app storage…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                    }.orEmpty()
                    require(displayName.substringAfterLast('.', "").equals("litertlm", true)) {
                        "Choose a .litertlm model file. Other formats are not supported by this runtime."
                    }
                    val folder = File(context.filesDir, "offline-models").apply { mkdirs() }
                    val target = File(folder, "active.litertlm")
                    val temporary = File(folder, "active.litertlm.part")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_MODEL_BYTES) { "Model file exceeds the 5 GB import safety limit." }
                                output.write(buffer, 0, count)
                            }
                            buffer.fill(0)
                        }
                    } ?: error("Android could not open the selected model file.")
                    require(temporary.length() >= MIN_MODEL_BYTES) { "Model file is too small to be a valid LiteRT-LM model." }
                    val backup = File(folder, "previous.litertlm.bak")
                    if (backup.exists()) backup.delete()
                    val hadPrevious = target.exists()
                    if (hadPrevious) require(target.renameTo(backup)) { "Could not preserve the previous model before replacing it." }
                    if (!temporary.renameTo(target)) {
                        if (hadPrevious) backup.renameTo(target)
                        error("Could not finalize the imported model file; previous model was restored where possible.")
                    }
                    backup.delete()
                    target.absolutePath
                }
            }
            working = false
            result.onSuccess { path ->
                modelPath = path
                prefs.edit().putString(MODEL_PATH_KEY, path).apply()
                status = "Model imported into private app storage. Run the RAM/storage preflight before loading it."
                answer = ""
            }.onFailure { error ->
                status = error.localizedMessage ?: "Model import failed."
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    Column(
        Modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(title = "OFFLINE AI MODEL", navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
        })
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("ON-DEVICE MODEL", color = colors.accent)
                Text(if (modelPath.isBlank()) "No model imported" else File(modelPath).name, color = colors.textPrimary)
                Text(status, color = colors.textSecondary)
                NovaButton(
                    text = "IMPORT .LITERTLM MODEL",
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth()
                )
                if (modelPath.isNotBlank()) {
                    NovaButton(
                        text = "CHECK DEVICE RAM & STORAGE",
                        onClick = {
                            working = true
                            scope.launch {
                                val check = withContext(Dispatchers.IO) { runCatching { LocalOfflineLlm.checkDevice(context, modelPath) } }
                                working = false
                                check.onSuccess { report = it.report; status = if (it.allowed) "Preflight passed. Runtime initialization can still fail on unsupported hardware." else "Preflight blocked. Use a smaller quantized model or free memory." }
                                    .onFailure { status = it.localizedMessage ?: "Device check failed." }
                            }
                        },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                        type = NovaButtonType.SECONDARY
                    )
                    NovaButton(
                        text = "REMOVE IMPORTED MODEL",
                        onClick = { showRemove = true },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                        type = NovaButtonType.TEXT
                    )
                }
                if (report.isNotBlank()) Text(report, color = colors.textSecondary)
            }
        }
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("LOCAL INFERENCE • NO CLOUD FALLBACK", color = colors.accent)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(6000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hindi / English prompt") },
                    minLines = 2
                )
                NovaButton(
                    text = if (working) "PROCESSING…" else "RUN OFFLINE MODEL",
                    onClick = {
                        if (modelPath.isBlank()) {
                            status = "पहले .litertlm model import करें।"
                        } else {
                            working = true
                            answer = ""
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { LocalOfflineLlm.generate(context, modelPath, prompt) }
                                }
                                working = false
                                result.onSuccess { answer = it; status = LocalOfflineLlm.lastRuntimeInfo }
                                    .onFailure { status = it.localizedMessage ?: "Offline inference failed." }
                            }
                        }
                    },
                    enabled = !working && modelPath.isNotBlank() && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (answer.isNotBlank()) Text(answer, color = colors.textPrimary)
                Text("This feature runs only after you import compatible model weights. Model files are not bundled in the APK because model size and licences vary. No fake response or automatic cloud substitution is used.", color = colors.textSecondary)
            }
        }
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("Remove offline model?") },
            text = { Text("This deletes the imported model file from this app's private storage and closes the active local model.") },
            confirmButton = {
                NovaButton(text = "Remove", onClick = {
                    showRemove = false
                    working = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                LocalOfflineLlm.close()
                                val file = File(modelPath)
                                if (file.exists()) require(file.delete()) { "Could not delete model file." }
                            }
                        }
                        working = false
                        result.onSuccess {
                            prefs.edit().remove(MODEL_PATH_KEY).apply()
                            modelPath = ""
                            report = ""
                            answer = ""
                            status = "Imported model removed."
                        }.onFailure { status = it.localizedMessage ?: "Model could not be removed." }
                    }
                })
            },
            dismissButton = { NovaButton(text = "Cancel", onClick = { showRemove = false }, type = NovaButtonType.TEXT) }
        )
    }
}

private const val MIN_MODEL_BYTES = 10_000_000L
private const val MAX_MODEL_BYTES = 5L * 1024L * 1024L * 1024L
