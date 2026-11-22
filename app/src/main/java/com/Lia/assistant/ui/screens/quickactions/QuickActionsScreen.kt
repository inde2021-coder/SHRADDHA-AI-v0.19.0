package com.Lia.assistant.ui.screens.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.Lia.assistant.action.InstalledAppLabelCache
import com.Lia.assistant.forge.ForgeController
import com.Lia.assistant.ui.theme.LocalNovaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import `in`.shraddha.ai.operator.OperatorActivity

private val PageLavender = Color(0xFFF4F0FF)
private val TileWhite = Color(0xFFFFFFFF)
private val Purple = Color(0xFF7134C8)
private val BodyText = Color(0xFF211B30)
private val Muted = Color(0xFF777087)

private data class QuickTile(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun QuickActionsScreen(navController: NavController, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = CoroutineScope(Dispatchers.Main)

    val tiles = listOf(
        QuickTile("Open an app", "Launch an installed app", Icons.Default.Apps) {
            context.startActivity(android.content.Intent(context, OperatorActivity::class.java))
        },
        QuickTile("Call someone", "Start a voice call", Icons.Default.Call) {
            navController.navigate("voice")
        },
        QuickTile("Send a message", "WhatsApp or SMS by voice", Icons.AutoMirrored.Filled.Message) {
            navController.navigate("voice")
        },
        QuickTile("Build a website", "Describe the site you need", Icons.Default.Web) {
            ForgeController.start(context, "Create a modern responsive website")
            navController.navigate("forge")
        },
        QuickTile("Visual Lens", "Ask about what you see", Icons.Default.CameraAlt) {
            navController.navigate("visual_lens")
        },
        QuickTile("Talk to Shraddha", "Start a voice conversation", Icons.Default.Mic) {
            navController.navigate("voice")
        },
        QuickTile("Ask anything", "Start a text conversation", Icons.Default.Language) {
            navController.navigate("chat")
        },
        QuickTile("Screen & phone control", "Read, tap, type and swipe", Icons.Default.PhoneAndroid) {
            context.startActivity(android.content.Intent(context, OperatorActivity::class.java))
        }
    )

    Column(
        modifier = modifier.fillMaxSize().background(PageLavender).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BodyText)
            }
            Column(Modifier.weight(1f)) {
                Text("Quick actions", color = BodyText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Pick one to start. You can also say it to Shraddha.", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(13.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier.fillMaxSize().padding(bottom = 10.dp)
        ) {
            items(tiles) { tile ->
                Column(
                    modifier = Modifier.fillMaxWidth().height(143.dp).clip(RoundedCornerShape(18.dp))
                        .background(TileWhite)
                        .border(1.dp, Color(0xFFE8E0F4), RoundedCornerShape(18.dp))
                        .clickable(onClick = tile.onClick)
                        .padding(13.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF0E7FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(tile.icon, contentDescription = null, tint = Purple, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(tile.title, color = BodyText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(3.dp))
                    Text(tile.description, color = Muted, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2)
                }
            }
        }
    }
}
