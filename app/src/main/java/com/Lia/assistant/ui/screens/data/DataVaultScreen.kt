package com.Lia.assistant.ui.screens.data

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.future.SecureBackupCodec
import `in`.shraddha.ai.future.UserBackupManager
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import `in`.shraddha.ai.brain.UnifiedMemoryRepository
import `in`.shraddha.ai.brain.LocalKnowledgeDocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@Composable
fun DataVaultScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    val assistantName = NovaPreferences.getString(context, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.FULL_NAME)
        .trim().ifBlank { AssistantBrand.FULL_NAME }
    val backupFileName = assistantName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .ifBlank { "assistant" } + "-backup.sba"
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var memoryRecords by remember { mutableStateOf(emptyList<String>()) }
    var selectedMemoryKey by remember { mutableStateOf("") }
    var cloudDeleteConsent by remember { mutableStateOf(false) }
    var cloudEmail by remember { mutableStateOf("") }
    var cloudPassword by remember { mutableStateOf("") }
    var showMemoryDeleteConfirmation by remember { mutableStateOf(false) }
    var showClearLocalMemoryConfirmation by remember { mutableStateOf(false) }
    var showClearAllMemoriesConfirmation by remember { mutableStateOf(false) }
    var memoryConflict by remember { mutableStateOf<UnifiedMemoryRepository.ConflictPreview?>(null) }
    var knowledgeDocuments by remember { mutableStateOf(emptyList<LocalKnowledgeDocumentStore.Document>()) }
    var knowledgeQuery by remember { mutableStateOf("") }
    var knowledgeResults by remember { mutableStateOf(emptyList<LocalKnowledgeDocumentStore.SearchHit>()) }
    var knowledgeCloudConsent by remember { mutableStateOf(false) }
    var knowledgeCloudEmail by remember { mutableStateOf("") }
    var knowledgeCloudPassword by remember { mutableStateOf("") }

    LaunchedEffect(context) {
        isBusy = true
        val loaded = withContext(Dispatchers.IO) {
            val memories = runCatching {
                EncryptedFeatureStore(context.applicationContext).getAllWithPrefixes(setOf("memory_")).getOrThrow()
            }
            val documents = runCatching {
                LocalKnowledgeDocumentStore(context.applicationContext).listDocuments().getOrThrow()
            }
            memories to documents
        }
        loaded.first.onSuccess { values ->
            memoryRecords = values.keys.map { it.removePrefix("memory_") }.sorted()
        }
        loaded.second.onSuccess { knowledgeDocuments = it }
        status = buildString {
            append("Memory: ").append(memoryRecords.size).append(" records • Knowledge: ")
            append(knowledgeDocuments.size).append(" documents")
            loaded.first.exceptionOrNull()?.let { append(" • Memory load error: ").append(it.message) }
            loaded.second.exceptionOrNull()?.let { append(" • Knowledge load error: ").append(it.message) }
        }
        isBusy = false
    }


    val knowledgePdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            status = "Knowledge PDF import cancelled."
        } else {
            scope.launch {
                isBusy = true
                val result = withContext(Dispatchers.IO) {
                    val label = uri.lastPathSegment?.substringAfterLast('/')?.take(120)?.ifBlank { "Imported PDF" } ?: "Imported PDF"
                    LocalKnowledgeDocumentStore(context.applicationContext).importPdf(
                        context = context.applicationContext,
                        consent = true,
                        id = java.util.UUID.randomUUID().toString().replace("-", ""),
                        title = label.removeSuffix(".pdf").take(160).ifBlank { "Imported PDF" },
                        sourceLabel = label,
                        pdfUri = uri
                    )
                }
                status = result.fold({ "PDF OCR import complete. Text is stored in encrypted local Knowledge Brain." },
                    { "PDF import failed: ${it.message}" })
                isBusy = false
                if (result.isSuccess) knowledgeDocuments =
                    LocalKnowledgeDocumentStore(context.applicationContext).listDocuments().getOrDefault(emptyList())
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val envelope = pendingExport
        pendingExport = null
        if (uri == null || envelope == null) {
            status = "Backup export cancelled. No file was written."
            isBusy = false
        } else {
            scope.launch {
                isBusy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Could not open the selected backup destination.")
                        output.use { it.write(envelope.toByteArray(StandardCharsets.UTF_8)) }
                    }
                }
                status = result.fold(
                    onSuccess = { "Encrypted backup file saved. Keep the passphrase separately; it cannot be recovered." },
                    onFailure = { it.message ?: "Could not save the encrypted backup file." }
                )
                isBusy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            status = "Backup import cancelled."
        } else {
            scope.launch {
                isBusy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected backup file.")
                        val bytes = input.use { stream ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            var total = 0
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 2_100_000) { "Encrypted backup exceeds the 2.1 MB import limit." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        String(bytes, StandardCharsets.UTF_8).also {
                            require(it.length in 40..2_100_000) { "Selected file is not a supported encrypted backup." }
                        }
                    }
                }
                result.onSuccess {
                    pendingImport = it
                    showRestoreConfirmation = true
                    status = "Encrypted file loaded. Confirm restore only if you trust this backup and know its passphrase."
                }.onFailure {
                    status = it.message ?: "Could not read the encrypted backup."
                }
                isBusy = false
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
            title = "DATA VAULT",
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
            }
        )

        NovaGlassCard {
            Text("PEOPLE MEMORY", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Shraddha की सेव की गई यादें यहाँ दिखती हैं। सूची से याद चुनें और उसे हटाएँ। Cloud copy के लिए Firebase अनुमति अलग से चाहिए।", color = colors.textSecondary, fontSize = 11.sp)
            NovaButton(
                text = if (isBusy) "लोड हो रहा है…" else "यादें रीफ़्रेश करें",
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            EncryptedFeatureStore(context.applicationContext).getAllWithPrefixes(setOf("memory_"))
                        }
                        result.onSuccess { values ->
                            memoryRecords = values.keys.map { it.removePrefix("memory_") }.sorted()
                            if (selectedMemoryKey !in memoryRecords) selectedMemoryKey = ""
                            status = "Loaded ${memoryRecords.size} local memory record(s)."
                        }.onFailure { status = "Could not read encrypted memory index: ${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.SECONDARY
            )
            if (memoryRecords.isEmpty()) {
                Text("अभी कोई स्थानीय याद नहीं मिली। यादें रीफ़्रेश करें।", color = colors.textTertiary, fontSize = 10.sp)
            } else {
                memoryRecords.take(50).forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(key, color = colors.textPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        NovaButton(
                            text = if (selectedMemoryKey == key) "चुना गया" else "चुनें",
                            onClick = { selectedMemoryKey = key },
                            type = NovaButtonType.TEXT
                        )
                    }
                }
            }
            NovaButton(
                text = "COMPARE LOCAL & CLOUD COPIES",
                onClick = {
                    if (!cloudDeleteConsent) status = "Enable explicit Firebase consent and enter credentials before comparing cloud copies."
                    else if (!selectedMemoryKey.matches(Regex("[A-Za-z0-9_-]{1,64}"))) status = "Select a valid memory record key first."
                    else scope.launch {
                        isBusy = true
                        val preview = withContext(Dispatchers.IO) {
                            UnifiedMemoryRepository(context.applicationContext).compareLocalAndCloud(
                                selectedMemoryKey, true, cloudEmail, cloudPassword
                            )
                        }
                        preview.onSuccess { memoryConflict = it; status = if (it.differs) "Conflict found. Choose which copy should win." else "No conflicting copies found, or only one copy exists." }
                            .onFailure { status = it.localizedMessage ?: "Could not compare memory copies." }
                        isBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.SECONDARY,
                enabled = !isBusy && selectedMemoryKey.matches(Regex("[A-Za-z0-9_-]{1,64}"))
            )
            memoryConflict?.let { conflict ->
                Text("Local: ${conflict.localValue?.take(180) ?: "Missing"}", color = colors.textSecondary, fontSize = 10.sp)
                Text("Cloud: ${conflict.cloudValue?.take(180) ?: "Missing"}", color = colors.textSecondary, fontSize = 10.sp)
                if (conflict.differs) {
                    NovaButton(text = "KEEP LOCAL • UPLOAD TO CLOUD", onClick = {
                        scope.launch {
                            isBusy = true
                            val result = withContext(Dispatchers.IO) {
                                UnifiedMemoryRepository(context.applicationContext).resolveConflict(conflict.key, "local", true, cloudEmail, cloudPassword)
                            }
                            result.onSuccess { status = it.message; memoryConflict = null }
                                .onFailure { status = it.localizedMessage ?: "Could not resolve conflict." }
                            isBusy = false
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !isBusy)
                    NovaButton(text = "KEEP CLOUD • RESTORE TO DEVICE", onClick = {
                        scope.launch {
                            isBusy = true
                            val result = withContext(Dispatchers.IO) {
                                UnifiedMemoryRepository(context.applicationContext).resolveConflict(conflict.key, "cloud", true, cloudEmail, cloudPassword)
                            }
                            result.onSuccess { status = it.message; memoryConflict = null }
                                .onFailure { status = it.localizedMessage ?: "Could not resolve conflict." }
                            isBusy = false
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !isBusy, type = NovaButtonType.SECONDARY)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cloudDeleteConsent, onCheckedChange = { cloudDeleteConsent = it; if (!it) cloudPassword = "" })
                Text("Allow authenticated Firebase operations (compare, resolve conflict or delete cloud copy)", color = colors.textSecondary, fontSize = 10.sp)
            }
            if (cloudDeleteConsent) {
                NovaTextField(value = cloudEmail, onValueChange = { cloudEmail = it.take(254) }, modifier = Modifier.fillMaxWidth(), placeholder = "Firebase account email")
                NovaTextField(value = cloudPassword, onValueChange = { cloudPassword = it.take(128) }, modifier = Modifier.fillMaxWidth(), placeholder = "Firebase password", secret = true)
            }
            NovaButton(
                text = "इस फोन की सभी यादें साफ़ करें",
                onClick = { showClearLocalMemoryConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.TEXT,
                enabled = !isBusy && memoryRecords.isNotEmpty()
            )
            NovaButton(
                text = "DELETE ALL LOCAL + FIREBASE MEMORY",
                onClick = {
                    if (!cloudDeleteConsent || cloudEmail.isBlank() || cloudPassword.length < 8) {
                        status = "Enable Firebase operations and enter the account email/password before full cloud deletion."
                    } else showClearAllMemoriesConfirmation = true
                },
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.TEXT,
                enabled = !isBusy && cloudDeleteConsent && cloudEmail.isNotBlank() && cloudPassword.length >= 8
            )
            NovaButton(
                text = "चुनी हुई याद हटाएँ",
                onClick = { if (selectedMemoryKey.matches(Regex("[A-Za-z0-9_-]{1,64}"))) showMemoryDeleteConfirmation = true else status = "Select a valid memory record key first." },
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.TEXT,
                enabled = !isBusy && selectedMemoryKey.matches(Regex("[A-Za-z0-9_-]{1,64}"))
            )
        }

        NovaGlassCard {
            Text("PERSONAL KNOWLEDGE BRAIN", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Import PDFs with Hindi/English OCR, search encrypted source-grounded notes, and optionally sync encrypted records across devices.", color = colors.textSecondary, fontSize = 11.sp)
            NovaButton(text = "Import PDF with OCR", onClick = { knowledgePdfLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth(), enabled = !isBusy)
            NovaButton(text = "Refresh knowledge documents", onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { LocalKnowledgeDocumentStore(context.applicationContext).listDocuments() }
                    result.onSuccess { knowledgeDocuments = it; status = "Loaded ${it.size} knowledge document(s)." }
                        .onFailure { status = "Could not load documents: ${it.message}" }
                }
            }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
            NovaTextField(value = knowledgeQuery, onValueChange = { knowledgeQuery = it.take(300) },
                modifier = Modifier.fillMaxWidth(), placeholder = "Search in Hindi or English")
            NovaButton(text = "Search Knowledge", onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { LocalKnowledgeDocumentStore(context.applicationContext).search(knowledgeQuery) }
                    result.onSuccess { knowledgeResults = it; status = "Found ${it.size} cited sections." }
                        .onFailure { status = "Search failed: ${it.message}" }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = knowledgeQuery.isNotBlank() && !isBusy, type = NovaButtonType.SECONDARY)
            knowledgeResults.forEach { hit ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(hit.title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(hit.excerpt, color = colors.textSecondary, fontSize = 10.sp)
                    Text(hit.citation, color = colors.accent, fontSize = 9.sp)
                }
            }
            Text("Documents: ${knowledgeDocuments.size}", color = colors.textSecondary, fontSize = 10.sp)
            knowledgeDocuments.forEach { document ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(document.title, color = colors.textPrimary, fontSize = 11.sp)
                        Text(document.sourceLabel, color = colors.textTertiary, fontSize = 9.sp)
                    }
                    NovaButton(text = "Delete", onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                LocalKnowledgeDocumentStore(context.applicationContext).delete(document.id)
                            }
                            status = result.fold({ "Knowledge document deleted." }, { "Delete failed: ${it.message}" })
                            knowledgeDocuments = LocalKnowledgeDocumentStore(context.applicationContext).listDocuments().getOrDefault(emptyList())
                            knowledgeResults = emptyList()
                        }
                    }, type = NovaButtonType.TEXT)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = knowledgeCloudConsent, onCheckedChange = {
                    knowledgeCloudConsent = it
                    if (!it) knowledgeCloudPassword = ""
                })
                Text("I explicitly approve encrypted Firebase knowledge sync", color = colors.textSecondary, fontSize = 10.sp)
            }
            if (knowledgeCloudConsent) {
                NovaTextField(value = knowledgeCloudEmail, onValueChange = { knowledgeCloudEmail = it.take(254) },
                    modifier = Modifier.fillMaxWidth(), placeholder = "Firebase account email")
                NovaTextField(value = knowledgeCloudPassword, onValueChange = { knowledgeCloudPassword = it.take(128) },
                    modifier = Modifier.fillMaxWidth(), placeholder = "Firebase password", secret = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaButton(text = "Sync", onClick = {
                        scope.launch {
                            isBusy = true
                            val result = withContext(Dispatchers.IO) {
                                LocalKnowledgeDocumentStore(context.applicationContext).syncToCloud(true, knowledgeCloudEmail, knowledgeCloudPassword)
                            }
                            knowledgeCloudPassword = ""
                            status = result.fold({ "Encrypted sync complete: $it document(s)." }, { "Cloud sync failed: ${it.message}" })
                            isBusy = false
                        }
                    }, modifier = Modifier.weight(1f), enabled = !isBusy && knowledgeCloudEmail.isNotBlank() && knowledgeCloudPassword.length >= 8)
                    NovaButton(text = "Restore", onClick = {
                        scope.launch {
                            isBusy = true
                            val result = withContext(Dispatchers.IO) {
                                LocalKnowledgeDocumentStore(context.applicationContext).restoreFromCloud(true, knowledgeCloudEmail, knowledgeCloudPassword)
                            }
                            knowledgeCloudPassword = ""
                            status = result.fold({ "Cloud restore complete: $it document(s)." }, { "Cloud restore failed: ${it.message}" })
                            if (result.isSuccess) knowledgeDocuments = LocalKnowledgeDocumentStore(context.applicationContext).listDocuments().getOrDefault(emptyList())
                            isBusy = false
                        }
                    }, modifier = Modifier.weight(1f), enabled = !isBusy && knowledgeCloudEmail.isNotBlank() && knowledgeCloudPassword.length >= 8,
                        type = NovaButtonType.SECONDARY)
                }
            }
        }

        NovaGlassCard(cornerRadius = 22.dp, elevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = colors.accent)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Encrypted Backup & Restore", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Passphrase-protected • User-controlled file location", color = colors.textSecondary, fontSize = 10.sp)
                }
            }
            Text(
                "This creates an encrypted backup file at a location you choose. Automatic cloud sync is not active; individual cloud-memory deletion is available only when Firebase is configured and you explicitly consent.",
                color = Color(0xFFFFC477), fontSize = 11.sp
            )
            Text(
                "Included: selected preferences, assistant name, tasks, knowledge graph, active project, digital-twin settings, conversation recap and audit trail.",
                color = colors.textSecondary, fontSize = 11.sp
            )
            Text(
                "Excluded: API keys, gateway tokens, passwords, biometrics, device-pairing secrets, Project Lab plans, Expert Studio results, multi-agent evidence, Personal Timeline/Skill/Routine records and imported Personal Knowledge documents.",
                color = colors.textSecondary, fontSize = 11.sp
            )
        }

        NovaGlassCard {
            Text("BACKUP PASSPHRASE", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            NovaTextField(
                value = passphrase,
                onValueChange = { passphrase = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "At least ${SecureBackupCodec.MIN_PASSPHRASE_LENGTH} characters",
                secret = true
            )
            NovaTextField(
                value = confirmation,
                onValueChange = { confirmation = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Confirm passphrase for export",
                secret = true
            )
            Text("The passphrase is never stored. SecureBackupCodec clears its CharArray after encryption/decryption.", color = colors.textTertiary, fontSize = 10.sp)
            NovaButton(
                text = if (isBusy) "Preparing…" else "Create Encrypted Backup",
                onClick = {
                    if (passphrase.length < SecureBackupCodec.MIN_PASSPHRASE_LENGTH) {
                        status = "Passphrase must contain at least 12 characters."
                    } else if (passphrase != confirmation) {
                        status = "Passphrase confirmation does not match."
                    } else {
                        isBusy = true
                        val secret = passphrase.toCharArray()
                        passphrase = ""
                        confirmation = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    try { UserBackupManager(context).export(secret) }
                                    finally { secret.fill('\u0000') }
                                }
                            }
                            result.onSuccess {
                                pendingExport = it
                                status = "Choose where to save the encrypted backup file."
                                exportLauncher.launch(backupFileName)
                            }.onFailure {
                                status = it.message ?: "Backup creation failed."
                                isBusy = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy
            )
        }

        NovaGlassCard {
            Text("RESTORE ENCRYPTED BACKUP", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Choose a previously exported .sba file. The file is decrypted and validated before app data is changed.", color = colors.textSecondary, fontSize = 11.sp)
            NovaButton(
                text = "Choose Backup File",
                onClick = {
                    if (passphrase.length < SecureBackupCodec.MIN_PASSPHRASE_LENGTH) {
                        status = "Enter the backup passphrase first (minimum 12 characters)."
                    } else {
                        importLauncher.launch(arrayOf("application/octet-stream", "application/*", "text/plain", "*/*"))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy,
                type = NovaButtonType.SECONDARY
            )
        }

        if (status.isNotBlank()) {
            NovaGlassCard {
                Text(status, color = colors.textSecondary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showMemoryDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showMemoryDeleteConfirmation = false },
            title = { Text("Delete memory record?") },
            text = {
                Text(
                    if (cloudDeleteConsent) "This deletes the selected encrypted record from Firebase mirrors and this device. The remote account will be authenticated first."
                    else "This deletes only the selected local encrypted record. Any cloud copy remains untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMemoryDeleteConfirmation = false
                    val key = selectedMemoryKey
                    scope.launch {
                        isBusy = true
                        val result = withContext(Dispatchers.IO) {
                            UnifiedMemoryRepository(context.applicationContext).delete(
                                key, cloudDeleteConsent, cloudEmail.takeIf { cloudDeleteConsent },
                                cloudPassword.takeIf { cloudDeleteConsent }
                            )
                        }
                        if (cloudDeleteConsent) cloudPassword = ""
                        status = result.fold(
                            onSuccess = { if (it) "Memory record deleted." else "Memory record was not found." },
                            onFailure = { "Memory deletion failed: ${it.message}" }
                        )
                        if (result.isSuccess) {
                            val refreshed = withContext(Dispatchers.IO) {
                                EncryptedFeatureStore(context.applicationContext).getAllWithPrefixes(setOf("memory_"))
                            }
                            memoryRecords = refreshed.getOrDefault(emptyMap()).keys.map { it.removePrefix("memory_") }.sorted()
                            selectedMemoryKey = ""
                            cloudPassword = ""
                        }
                        isBusy = false
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showMemoryDeleteConfirmation = false }) { Text("Cancel") } }
        )
    }

    if (showClearAllMemoriesConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllMemoriesConfirmation = false },
            title = { Text("Delete every memory copy?") },
            text = { Text("This permanently deletes all records in this account's Firebase vault (Firestore and configured Realtime Database mirror) and all local memory_ records on this device. Export an encrypted backup first. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllMemoriesConfirmation = false
                    scope.launch {
                        isBusy = true
                        val result = withContext(Dispatchers.IO) {
                            UnifiedMemoryRepository(context.applicationContext).clearAll(true, cloudEmail, cloudPassword)
                        }
                        status = result.fold(
                            onSuccess = { memoryRecords = emptyList(); selectedMemoryKey = ""; memoryConflict = null; cloudPassword = ""; it.message },
                            onFailure = { "Full memory deletion did not complete: ${it.message}" }
                        )
                        isBusy = false
                    }
                }) { Text("Delete everywhere") }
            },
            dismissButton = { TextButton(onClick = { showClearAllMemoriesConfirmation = false }) { Text("Cancel") } }
        )
    }

    if (showClearLocalMemoryConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearLocalMemoryConfirmation = false },
            title = { Text("Permanently clear all local memories?") },
            text = {
                Text("This permanently deletes every encrypted memory_ record stored on this device. Firebase cloud copies are not changed. Export a backup first if you may need these records later.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearLocalMemoryConfirmation = false
                    scope.launch {
                        isBusy = true
                        val result = withContext(Dispatchers.IO) {
                            UnifiedMemoryRepository(context.applicationContext).clearLocal()
                        }
                        status = result.fold(
                            onSuccess = {
                                memoryRecords = emptyList()
                                selectedMemoryKey = ""
                                "All local encrypted memory records were deleted. Firebase cloud copies were not changed."
                            },
                            onFailure = { "Could not clear local memories: ${it.message}" }
                        )
                        isBusy = false
                    }
                }) { Text("Delete all local memories") }
            },
            dismissButton = {
                TextButton(onClick = { showClearLocalMemoryConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    if (showRestoreConfirmation && pendingImport != null) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false; pendingImport = null; passphrase = ""; confirmation = "" },
            title = { Text("Restore this backup?") },
            text = {
                Text("This can replace assistant preferences, tasks, knowledge graph, active project, digital-twin settings and conversation memory. API keys, gateway tokens, biometrics and device-pairing secrets are not restored. Continue only if you trust the file. This operation cannot be automatically rolled back.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val envelope = pendingImport ?: return@TextButton
                    val secret = passphrase.toCharArray()
                    passphrase = ""
                    confirmation = ""
                    showRestoreConfirmation = false
                    pendingImport = null
                    isBusy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try { UserBackupManager(context).restore(envelope, secret) } finally { secret.fill('\u0000') }
                        }
                        status = result.fold(
                            onSuccess = { "Backup restored. Reopen affected screens to refresh their state." },
                            onFailure = { it.message ?: "Backup restore failed. Existing data may need review if a storage write failed." }
                        )
                        isBusy = false
                    }
                }) { Text("Restore and Replace") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = false; pendingImport = null; passphrase = ""; confirmation = "" }) { Text("Cancel") }
            }
        )
    }
}
