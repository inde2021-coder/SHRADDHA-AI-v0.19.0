package com.Lia.assistant.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.platform.LocalContext
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.future.MilestoneState
import `in`.shraddha.ai.future.ProjectPlan
import `in`.shraddha.ai.future.ProjectPlanStore
import `in`.shraddha.ai.future.ProjectWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun ProjectLabScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var milestoneText by remember { mutableStateOf("") }
    var sequentialDependencies by remember { mutableStateOf(true) }
    var plans by remember { mutableStateOf(emptyList<ProjectPlan>()) }
    var status by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectPlan?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                            ?: error("Could not open the selected backup destination.")
                    }
                }
                status = result.fold({ "Project backup exported successfully." }, { "Backup export failed: ${it.message}" })
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isSaving = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected backup.")
                        val raw = input.use { readBoundedText(it, 1_000_000) }
                        ProjectPlanStore(context).importJson(raw).getOrThrow()
                    }
                }
                result.onSuccess {
                    plans = it
                    status = "Backup restored. Existing projects were preserved."
                }.onFailure {
                    status = "Backup import failed: ${it.message ?: "invalid backup"}"
                }
                isSaving = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            ProjectPlanStore(context).loadAll()
        }
        result.onSuccess {
            plans = it
            status = ""
        }.onFailure {
            // An unreadable encrypted vault is not an empty project list.
            status = "Project vault could not be opened: ${it.message ?: "encrypted storage unavailable"}. Existing plans were not changed."
        }
    }

    fun persist(plan: ProjectPlan) {
        scope.launch {
            isSaving = true
            val result = withContext(Dispatchers.IO) { ProjectPlanStore(context).save(plan) }
            result.onSuccess {
                plans = it
                status = "Project plan saved securely."
            }.onFailure {
                status = it.message ?: "Could not save the project plan."
            }
            isSaving = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(
            title = "PROJECT LAB",
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
            }
        )

        NovaGlassCard(cornerRadius = 22.dp, elevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, contentDescription = null, tint = colors.accent)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Local-first Project Planner", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Milestones • Dependencies • Progress • Encrypted storage", color = colors.textSecondary, fontSize = 10.sp)
                }
            }
            Text("Project title", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            NovaTextField(
                value = title,
                onValueChange = { title = it.take(160) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "e.g. Build my business website",
                singleLine = true
            )
            Text("Milestones (one per line, maximum 30)", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            NovaTextField(
                value = milestoneText,
                onValueChange = { milestoneText = it.take(5_000) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                placeholder = "Research requirements\nCreate design\nBuild first version\nReview and publish",
                singleLine = false
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = sequentialDependencies,
                    onCheckedChange = { sequentialDependencies = it },
                    enabled = !isSaving
                )
                Column(Modifier.weight(1f)) {
                    Text("Require milestones in order", color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("Each step unlocks after the previous step is marked DONE.", color = colors.textSecondary, fontSize = 9.sp)
                }
            }
            NovaButton(
                text = if (isSaving) "Saving…" else "Create Project Plan",
                onClick = {
                    val milestones = milestoneText.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    if (title.isBlank()) status = "Project title is required."
                    else if (milestones.isEmpty() || milestones.size > 30) status = "Add between 1 and 30 unique milestones."
                    else {
                        val created = runCatching { ProjectWorkspace.create(title, milestones, sequentialDependencies) }
                        created.onSuccess {
                            persist(it)
                            title = ""
                            milestoneText = ""
                        }.onFailure { status = it.message ?: "Could not create project plan." }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )
        }

        NovaGlassCard(cornerRadius = 18.dp, elevation = 3.dp) {
            Text("Project backup", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Export a portable JSON copy or restore one. Restore rejects duplicate IDs and never overwrites existing plans.", color = colors.textSecondary, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaButton(
                    text = "Export backup",
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { ProjectPlanStore(context).exportJson() }
                            result.onSuccess {
                                pendingExportJson = it
                                exportLauncher.launch("shraddha-projects-backup.json")
                            }.onFailure { status = "Backup export failed: ${it.message}" }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving && plans.isNotEmpty()
                )
                NovaButton(
                    text = "Restore backup",
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving
                )
            }
        }

        if (status.isNotBlank()) {
            NovaGlassCard {
                Text(status, color = colors.textSecondary, fontSize = 11.sp)
            }
        }

        if (plans.isEmpty()) {
            NovaGlassCard {
                Text("No project plans yet. Create one above; plans stay encrypted on this device.", color = colors.textSecondary, fontSize = 12.sp)
            }
        } else {
            plans.forEach { plan ->
                NovaGlassCard(cornerRadius = 20.dp, elevation = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("${ProjectWorkspace.progress(plan)}% complete • ${plan.milestones.size} milestones", color = colors.textSecondary, fontSize = 10.sp)
                        }
                        TextButton(onClick = { pendingDelete = plan }) {
                            Text("Delete", color = Color(0xFFFF9A9A))
                        }
                    }
                    plan.milestones.forEach { milestone ->
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text(milestone.title, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (milestone.note.isNotBlank()) {
                                Text(milestone.note, color = colors.textTertiary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    MilestoneState.TODO to "TODO",
                                    MilestoneState.IN_PROGRESS to "DOING",
                                    MilestoneState.BLOCKED to "BLOCKED",
                                    MilestoneState.DONE to "DONE"
                                ).forEach { (state, label) ->
                                    FilterChip(
                                        selected = milestone.state == state,
                                        onClick = {
                                            val updated = runCatching {
                                                ProjectWorkspace.update(plan, milestone.id, state, milestone.note)
                                            }
                                            updated.onSuccess { persist(it) }
                                                .onFailure { status = it.message ?: "Milestone update failed." }
                                        },
                                        label = { Text(label, fontSize = 9.sp) },
                                        enabled = !isSaving
                                    )
                                }
                            }
                        }
                    }
                    Text("DONE means you marked this milestone complete; it is not an independent verification by Shraddha.", color = colors.textTertiary, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    pendingDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete project plan?") },
            text = { Text("This permanently deletes ‘${plan.title}’ and its saved milestones from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { ProjectPlanStore(context).delete(plan.id) }
                        result.onSuccess { plans = it; status = "Project deleted." }
                            .onFailure { status = it.message ?: "Could not delete project." }
                        pendingDelete = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/** Reads an untrusted backup with a strict byte cap before parsing it. */
private fun readBoundedText(input: java.io.InputStream, maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Backup file exceeds the 1 MB limit." }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
