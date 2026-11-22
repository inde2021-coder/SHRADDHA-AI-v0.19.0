package com.Lia.assistant.ui.screens.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import `in`.shraddha.ai.R
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.ui.components.AccessKeySection
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky

@Composable
fun ProfileScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val assistantName = appState.assistantName.ifBlank { AssistantBrand.FULL_NAME }
    var nameInput by remember { mutableStateOf(appState.userName) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // The selected image remains usable for this session if the provider disallows persistence.
            }
            appState.applyAssistantAvatar(context, uri)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky().padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text("$assistantName Profile", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier.align(Alignment.CenterHorizontally).size(132.dp)
                .clip(CircleShape).background(colors.accentGlow),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = appState.assistantAvatarUri ?: R.drawable.shraddha_ai_hero,
                contentDescription = "$assistantName avatar",
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "${assistantName.uppercase()} AVATAR",
            color = colors.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(8.dp))
        NovaButton(text = "Change Image", onClick = { avatarPicker.launch(arrayOf("image/*")) })
        NovaButton(
            text = "Use Default $assistantName Image",
            onClick = { appState.applyAssistantAvatar(context, null) },
            type = NovaButtonType.TEXT
        )

        Spacer(modifier = Modifier.height(18.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Your Display Name", fontSize = 13.sp, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                NovaTextField(value = nameInput, onValueChange = { nameInput = it }, placeholder = "Enter your name")
                Spacer(modifier = Modifier.height(12.dp))
                NovaButton(text = "Save Name", onClick = { appState.applyUserName(context, nameInput) })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        AccessKeySection()
    }
}
