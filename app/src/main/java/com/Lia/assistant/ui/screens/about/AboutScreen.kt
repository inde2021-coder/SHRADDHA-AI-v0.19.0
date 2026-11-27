package com.Lia.assistant.ui.screens.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import `in`.shraddha.ai.R

private const val DEVELOPER_NAME = "Shrad Patel"
private const val DEVELOPER_EMAIL = "inde2021@gmail.com"

@Composable
fun AboutScreen(navController: NavController, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val assistantName = NovaPreferences.getString(
        context, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.FULL_NAME
    ).trim().ifBlank { AssistantBrand.FULL_NAME }
    val details = androidx.compose.runtime.remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            Triple(info.versionName ?: "Unknown", versionCode, context.packageName)
        }.getOrElse { Triple("Unknown", 0L, context.packageName) }
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text("About Shraddha AI", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(76.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    LiaOrb3D(state = NovaOrbState.IDLE, size = 76.dp, amplitude = 0f, assistantName = assistantName)
                }
                Spacer(Modifier.height(8.dp))
                Text(assistantName, fontFamily = SerifFontFamily, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                Text("YOUR PERSONAL AI COMPANION", color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                Spacer(Modifier.height(6.dp))
                Text("Version ${details.first}  •  Build ${details.second}", color = colors.textSecondary, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("THE CREATOR", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(12.dp))
                Image(
                    painter = painterResource(R.drawable.shraddha_developer),
                    contentDescription = "Shraddha AI developer portrait",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                        .clip(RoundedCornerShape(22.dp)).shadow(8.dp, RoundedCornerShape(22.dp))
                )
                Spacer(Modifier.height(14.dp))
                Text(DEVELOPER_NAME, color = colors.textPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("FOUNDER & DEVELOPER • SHRADDHA AI", color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "The creator behind Shraddha AI — bringing voice-first assistance, useful AI tools and connected device experiences together in one app.",
                    color = colors.textSecondary, fontSize = 13.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable { copyDeveloperEmail(context) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, tint = colors.accent)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Developer contact", color = colors.textSecondary, fontSize = 10.sp)
                        Text(DEVELOPER_EMAIL, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy email", tint = colors.accent)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        InfoCard("About Shraddha AI", Icons.Default.Info) {
            Text(
                "Shraddha AI is designed as one personal assistant for Hindi, Hinglish and English. Its experience combines voice interaction, AI chat, memory and optional phone and PC companion tools.",
                color = colors.textPrimary, fontSize = 13.sp, lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        InfoCard("What Shraddha can do", Icons.Default.Security) {
            AboutBullet("Talk and listen with voice controls")
            AboutBullet("Chat using supported AI providers")
            AboutBullet("Use Hindi, Hinglish and English")
            AboutBullet("Keep conversation history and optional memory")
            AboutBullet("Use Visual Lens and camera-based assistance")
            AboutBullet("Offer optional accessibility-based phone and app control")
            AboutBullet("Connect with the Windows companion for supported PC features")
            AboutBullet("Personalize assistant name, avatar, voice and orb style")
            Text("Some capabilities need permissions, setup, internet access or a compatible device.", color = colors.textSecondary, fontSize = 10.sp, lineHeight = 15.sp)
        }

        Spacer(Modifier.height(12.dp))
        InfoCard("Technology & app details", Icons.Default.Code) {
            AboutDetail("AI providers", "Gemini • OpenAI • Groq • Claude • Perplexity")
            AboutDetail("Languages", "Hindi • Hinglish • English")
            AboutDetail("Application ID", details.third)
            AboutDetail("Android support", "Android 8.0 (API 26) and newer")
        }

        Spacer(Modifier.height(14.dp))
        Text("MADE WITH CARE IN INDIA", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(5.dp))
        Text("© 2026 Shrad Patel • Shraddha AI", color = colors.textSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun InfoCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalNovaColors.current
    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = colors.accent)
                Text(title, color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 9.dp))
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AboutBullet(text: String) {
    val colors = LocalNovaColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("✦", color = colors.accent, fontSize = 11.sp)
        Text(text, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AboutDetail(label: String, value: String) {
    val colors = LocalNovaColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = colors.textSecondary, fontSize = 10.sp)
        Text(value, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun copyDeveloperEmail(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Shraddha AI developer email", DEVELOPER_EMAIL))
    Toast.makeText(context, "Developer email copied", Toast.LENGTH_SHORT).show()
}
