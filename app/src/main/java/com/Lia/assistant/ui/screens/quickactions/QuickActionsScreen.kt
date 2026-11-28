package com.Lia.assistant.ui.screens.quickactions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Web
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.forge.ForgeController
import com.Lia.assistant.ui.theme.LiaReferenceTheme
import com.Lia.assistant.ui.theme.LocalNovaColors
import `in`.shraddha.ai.operator.OperatorActivity
import `in`.shraddha.ai.learning.CurriculumLearningActivity

private data class ActionItem(
    val title: String,
    val explanation: String,
    val icon: ImageVector,
    val action: () -> Unit
)

@Composable
fun QuickActionsScreen(navController: NavController, modifier: Modifier = Modifier) {
    LiaReferenceTheme {
        val colors = LocalNovaColors.current
        val context = LocalContext.current
        val assistantName = NovaPreferences.getString(
            context, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.FULL_NAME
        ).trim().ifBlank { AssistantBrand.FULL_NAME }
        var showMore by remember { mutableStateOf(false) }

        val mainActions = listOf(
            ActionItem("बात करें", "\${assistantName} से आवाज़ में बात करें", Icons.Default.Mic) {
                navController.navigate("voice")
            },
            ActionItem("संदेश लिखें", "टेक्स्ट में सवाल या निर्देश दें", Icons.AutoMirrored.Filled.Message) {
                navController.navigate("chat")
            },
            ActionItem("सीखें", "गीता के 18 अध्याय, पाठ्यक्रम और सुरक्षित learning progress", Icons.Default.School) {
                context.startActivity(android.content.Intent(context, CurriculumLearningActivity::class.java))
            },
            ActionItem("कैमरे से पूछें", "सामने की चीज़ को कैमरे से समझें", Icons.Default.CameraAlt) {
                navController.navigate("visual_lens")
            },
            ActionItem("फोन से PC चलाएँ", "पहले PC जोड़ें, फिर बोलकर या लिखकर आदेश दें", Icons.Default.Devices) {
                navController.navigate("device_center")
            },
            ActionItem("फोन ऐप चलाएँ", "अनुमति देकर ऐप पर काम करवाएँ", Icons.Default.AutoAwesome) {
                context.startActivity(android.content.Intent(context, OperatorActivity::class.java))
            }
        )
        val moreActions = listOf(
            ActionItem("Expert Studio", "विशेष सवालों और तकनीकी मदद के लिए", Icons.Default.AutoAwesome) {
                navController.navigate("expert_studio")
            },
            ActionItem("Project Lab", "बड़े काम की योजना और प्रगति", Icons.Default.Web) {
                navController.navigate("project_lab")
            },
            ActionItem("यादें और बैकअप", "सेव की गई यादें और सुरक्षित कॉपी", Icons.Default.Security) {
                navController.navigate("data_vault")
            },
            ActionItem("वेबसाइट बनाना", "वेबसाइट का काम शुरू करें", Icons.Default.Web) {
                ForgeController.start(context, "Create a modern responsive website")
                navController.navigate("forge")
            },
            ActionItem("अन्य AI टूल", "अतिरिक्त सुविधाएँ", Icons.Default.Security) {
                navController.navigate("agent_council")
            }
        )

        Column(
            modifier = modifier.fillMaxSize().background(colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
                Column {
                    Text("आप क्या करना चाहते हैं?", color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("काम चुनें — बाकी निर्देश Shraddha को बोलें या लिखें।", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
            Text("रोज़ के काम", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            mainActions.forEach { ActionCard(it, colors.textPrimary, colors.textSecondary) }
            Column(
                Modifier.fillMaxWidth().animateContentSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFF1EAFE))
                    .border(1.dp, Color(0xFFE3D7FA), RoundedCornerShape(20.dp))
                    .clickable { showMore = !showMore }.padding(15.dp)
            ) {
                Text(if (showMore) "कम उपयोग होने वाले टूल छिपाएँ  ▲" else "अन्य सुविधाएँ  ▼",
                    color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("ये रोज़ इस्तेमाल के लिए ज़रूरी नहीं हैं।", color = colors.textSecondary, fontSize = 10.sp)
                AnimatedVisibility(showMore) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                        moreActions.forEach { ActionCard(it, colors.textPrimary, colors.textSecondary) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ActionCard(item: ActionItem, titleColor: Color, detailColor: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFFFFFFF))
            .border(1.dp, Color(0xFFE3D7FA), RoundedCornerShape(18.dp))
            .clickable(onClick = item.action).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(item.icon, contentDescription = null, tint = Color(0xFF7C3AED))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(item.explanation, color = detailColor, fontSize = 10.sp)
        }
        Text("›", color = Color(0xFF8B5CF6), fontSize = 22.sp)
    }
}
