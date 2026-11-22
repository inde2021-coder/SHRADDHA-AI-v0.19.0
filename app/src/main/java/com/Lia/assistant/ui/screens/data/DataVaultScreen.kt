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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.future.SecureBackupCodec
import `in`.shraddha.ai.future.UserBackupManager
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
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }

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

        NovaGlassCard(cornerRadius = 22.dp, elevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = colors.accent)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Encrypted Backup & Restore", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Passphrase-protected • User-controlled file location", color = colors.textSecondary, fontSize = 10.sp)
                }
            }
            Text(
                "This creates an encrypted backup file on a location you choose. Cloud sync is not active in this build; you may choose a cloud-drive folder through Android's file picker.",
                color = Color(0xFFFFC477), fontSize = 11.sp
            )
            Text(
                "Included: selected preferences, assistant name, tasks, knowledge graph, active project, digital-twin settings, conversation recap and audit trail.",
                color = colors.textSecondary, fontSize = 11.sp
            )
            Text(
                "Excluded: API keys, gateway tokens, passwords, biometric templates and device-pairing secrets.",
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
                                runCatching { UserBackupManager(context).export(secret) }
                            }
                            result.onSuccess {
                                pendingExport = it
                                status = "Choose where to save the encrypted backup file."
                                exportLauncher.launch("shraddha-backup.sba")
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

    if (showRestoreConfirmation && pendingImport != null) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false; pendingImport = null },
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
                            UserBackupManager(context).restore(envelope, secret)
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
                TextButton(onClick = { showRestoreConfirmation = false; pendingImport = null }) { Text("Cancel") }
            }
        )
    }
}
