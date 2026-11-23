package com.Lia.assistant.ui.screens.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.NovaOrbState

private const val DEVELOPER_NAME = "Shrad Patel"
private const val DEVELOPER_EMAIL = "inde2021@gmail.com"
private const val COPYRIGHT_TEXT = "© 2026 Shrad Patel. All rights reserved."

@Composable
fun AboutScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val assistantName = NovaPreferences.getString(
        context,
        NovaPreferences.Keys.ASSISTANT_NAME,
        AssistantBrand.FULL_NAME
    ).trim().ifBlank { AssistantBrand.FULL_NAME }

    val appDetails = rememberAppDetails(context)

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text("About $assistantName", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))
        androidx.compose.foundation.layout.Box(Modifier.size(112.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            LiaOrb3D(state = NovaOrbState.IDLE, size = 112.dp, amplitude = 0f, assistantName = assistantName)
        }
        Spacer(Modifier.height(12.dp))
        Text(assistantName, fontFamily = SerifFontFamily, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text("YOUR PERSONAL AI COMPANION", color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(7.dp))
        Text("Version ${appDetails.versionName}  •  Build ${appDetails.versionCode}", color = colors.textSecondary, fontSize = 12.sp)

        Spacer(Modifier.height(20.dp))
        InfoCard(title = "About the app", icon = { Icon(Icons.Default.Info, null, tint = colors.accent) }) {
            Text(
                "$assistantName is a personal AI companion designed for Hindi, Hinglish and English conversations. It brings voice interaction, AI chat with configurable providers, conversation memory, visual tools and optional device assistance into one Android app.",
                color = colors.textPrimary, fontSize = 13.sp, lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        InfoCard(title = "App features", icon = { Icon(Icons.Default.Security, null, tint = colors.accent) }) {
            AboutBullet("Voice assistant with listening, speaking and mute controls")
            AboutBullet("AI chat and provider selection")
            AboutBullet("Personality, language and assistant-name settings")
            AboutBullet("Conversation history and optional saved memory")
            AboutBullet("Visual Lens camera tools")
            AboutBullet("Optional phone, app and screen assistance")
            AboutBullet("Custom assistant avatar and animated orb styles")
            AboutBullet("Some features require user permissions, service setup or a compatible device")
        }

        Spacer(Modifier.height(12.dp))
        InfoCard(title = "App information", icon = { Icon(Icons.Default.Info, null, tint = colors.accent) }) {
            AboutDetail("Application ID", appDetails.applicationId)
            AboutDetail("Minimum Android", "Android 8.0 (API 26) or newer")
            AboutDetail("Languages", "Hindi • Hinglish • English")
            AboutDetail("AI provider options", "Gemini • OpenAI • Groq • Claude • Perplexity")
        }

        Spacer(Modifier.height(12.dp))
        InfoCard(title = "Developer & contact", icon = { Icon(Icons.Default.Email, null, tint = colors.accent) }) {
            Text("Developer", color = colors.textSecondary, fontSize = 11.sp)
            Text(DEVELOPER_NAME, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { copyDeveloperEmail(context, assistantName) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Email", color = colors.textSecondary, fontSize = 11.sp)
                    Text(DEVELOPER_EMAIL, color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { copyDeveloperEmail(context, assistantName) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy developer email", tint = colors.accent)
                }
            }
            Text("Tap the email row or copy icon to copy the address.", color = colors.textSecondary, fontSize = 10.sp)
        }

        Spacer(Modifier.height(12.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(COPYRIGHT_TEXT, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text("${assistantName.uppercase()} • Built with care in India", color = colors.textSecondary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoCard(title: String, icon: @Composable () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalNovaColors.current
    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Text(title, color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 9.dp))
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AboutDetail(label: String, value: String) {
    val colors = LocalNovaColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = colors.textSecondary, fontSize = 10.sp)
        Text(value, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AboutBullet(text: String) {
    val colors = LocalNovaColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(text, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

private data class ShraddhaAppDetails(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String
)

@Composable
private fun rememberAppDetails(context: Context): ShraddhaAppDetails {
    return androidx.compose.runtime.remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            ShraddhaAppDetails(
                versionName = info.versionName?.takeIf { it.isNotBlank() } ?: "Unknown",
                versionCode = code,
                applicationId = context.packageName
            )
        }.getOrElse {
            ShraddhaAppDetails("Unknown", 0L, context.packageName)
        }
    }
}

private fun copyDeveloperEmail(context: Context, assistantName: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("$assistantName developer email", DEVELOPER_EMAIL))
    Toast.makeText(context, "Developer email copied", Toast.LENGTH_SHORT).show()
}
