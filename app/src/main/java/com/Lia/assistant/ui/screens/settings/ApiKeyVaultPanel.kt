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
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    NovaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, elevation = 5.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("API Key Lock", color = colors.textPrimary)
            Text(
                "Shraddha normally keeps provider keys encrypted by Android Keystore, without a separate password. Remove the old password layer below; your saved keys will be migrated safely and will not be deleted.",
                color = colors.textSecondary
            )

            if (!configured) {
                Text("No separate API-key password lock is active. Your keys continue to use Android Keystore encryption.", color = colors.success)
            } else if (!unlocked) {
                Text("Unlock the existing vault one last time. Shraddha will then remove the password layer and restore your saved keys to Android Keystore encryption.", color = colors.textSecondary)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Current vault password") },
                    visualTransformation = PasswordVisualTransformation()
                )
                NovaButton(
                    text = if (busy) "Unlocking…" else "Unlock once",
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
                            message = if (unlocked) "Unlocked. Now remove the password lock below." else "Password incorrect. Existing keys were not changed."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("Existing vault unlocked. Your saved keys are ready to migrate.", color = colors.success)
                NovaButton(
                    text = if (busy) "Removing lock…" else "Remove password lock",
                    type = NovaButtonType.SECONDARY,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { ApiKeyVaultMigration.disable(context) }
                            }
                            busy = false
                            result.onSuccess {
                                configured = false
                                unlocked = false
                                message = "Password lock removed. All saved keys were verified and preserved."
                                onAccessChanged(true)
                            }.onFailure {
                                message = it.message ?: "Could not remove the password lock. Your existing vault was preserved."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (message.isNotBlank()) Text(message, color = if (message.contains("incorrect") || message.contains("Could not")) colors.error else colors.textSecondary)
        }
    }
}
