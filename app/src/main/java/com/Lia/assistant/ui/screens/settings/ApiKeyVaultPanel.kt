package com.Lia.assistant.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.Lia.assistant.data.ApiKeyVaultMigration
import com.Lia.assistant.data.PasswordProtectedApiKeyVault
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.theme.LocalNovaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ApiKeyVaultPanel(context: Context, onAccessChanged: (Boolean) -> Unit) {
    val colors = LocalNovaColors.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(PasswordProtectedApiKeyVault.isConfigured(context)) }
    var unlocked by remember { mutableStateOf(PasswordProtectedApiKeyVault.isUnlocked()) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    NovaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, elevation = 5.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Password-protected API key vault", color = colors.textPrimary)
            Text(
                "Provider keys are encrypted with AES-256-GCM. A password-derived key stays in memory only while unlocked. The vault locks automatically when Shraddha leaves the foreground. Backend Mode remains separate.",
                color = colors.textSecondary
            )
            if (!configured) {
                Text("Create a password to move your existing encrypted provider keys into the protected vault.", color = colors.textSecondary)
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("New vault password (10+ characters)") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Confirm vault password") },
                    visualTransformation = PasswordVisualTransformation()
                )
                NovaButton(
                    text = if (busy) "Enabling…" else "Enable password protection",
                    enabled = !busy && password.isNotBlank() && confirm.isNotBlank(),
                    onClick = {
                        if (password != confirm) {
                            message = "Passwords do not match."
                        } else {
                            busy = true
                            val secret = password.toCharArray()
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { ApiKeyVaultMigration.enable(context, secret) }
                                }
                                secret.fill('\u0000')
                                busy = false
                                result.onSuccess {
                                    configured = true
                                    unlocked = true
                                    password = ""
                                    confirm = ""
                                    message = "Password protection enabled. Existing provider keys were migrated."
                                    onAccessChanged(true)
                                }.onFailure {
                                    message = it.message ?: "Could not enable password protection."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!unlocked) {
                Text("Vault is locked. Unlock it to view, test, add, or remove provider keys.", color = colors.textSecondary)
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Vault password") },
                    visualTransformation = PasswordVisualTransformation()
                )
                NovaButton(
                    text = if (busy) "Unlocking…" else "Unlock vault",
                    enabled = !busy && password.isNotBlank(),
                    onClick = {
                        busy = true
                        val secret = password.toCharArray()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { PasswordProtectedApiKeyVault.unlock(context, secret) }
                            }
                            secret.fill('\u0000')
                            busy = false
                            password = ""
                            unlocked = result.getOrDefault(false)
                            message = if (unlocked) "Vault unlocked." else "Incorrect password or damaged vault data."
                            onAccessChanged(unlocked)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("Vault unlocked for this foreground session.", color = colors.success)
                NovaButton(
                    text = "Lock vault now",
                    type = NovaButtonType.SECONDARY,
                    onClick = {
                        PasswordProtectedApiKeyVault.lock()
                        unlocked = false
                        onAccessChanged(false)
                        message = "Vault locked."
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Change vault password", color = colors.textPrimary)
                OutlinedTextField(
                    value = currentPassword, onValueChange = { currentPassword = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = newPassword, onValueChange = { newPassword = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("New password (10+ characters)") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = confirmNewPassword, onValueChange = { confirmNewPassword = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Confirm new password") },
                    visualTransformation = PasswordVisualTransformation()
                )
                NovaButton(
                    text = if (busy) "Updating…" else "Change password",
                    type = NovaButtonType.SECONDARY,
                    enabled = !busy && currentPassword.isNotBlank() && newPassword.isNotBlank() && confirmNewPassword.isNotBlank(),
                    onClick = {
                        if (newPassword != confirmNewPassword) {
                            message = "New passwords do not match."
                        } else {
                            busy = true
                            val oldSecret = currentPassword.toCharArray()
                            val newSecret = newPassword.toCharArray()
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { PasswordProtectedApiKeyVault.changePassword(context, oldSecret, newSecret) }
                                }
                                oldSecret.fill('\u0000')
                                newSecret.fill('\u0000')
                                busy = false
                                currentPassword = ""
                                newPassword = ""
                                confirmNewPassword = ""
                                message = result.fold(
                                    { "Vault password changed. Your saved API keys were re-encrypted." },
                                    { it.message ?: "Could not change password." }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (message.isNotBlank()) Text(message, color = if (message.contains("not") || message.contains("Incorrect") || message.contains("Could not")) colors.error else colors.textSecondary)
            Text(
                "Important: if you forget this password, the protected keys cannot be recovered. Remove/re-enter keys only after understanding that reset or app-data loss can permanently erase them.",
                color = colors.textSecondary
            )
        }
    }
}
