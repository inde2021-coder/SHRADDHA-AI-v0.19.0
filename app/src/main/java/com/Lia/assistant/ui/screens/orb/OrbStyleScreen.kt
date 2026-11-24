package com.Lia.assistant.ui.screens.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.fx.DailyOrbThemeSchedule
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.rememberDailyOrbTheme
import com.Lia.assistant.ui.fx.NovaOrbStyle
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.NovaOrbState
import java.util.Calendar

@Composable
fun OrbStyleScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val dailyTheme = rememberDailyOrbTheme()

    var previewState by remember { mutableStateOf(NovaOrbState.IDLE) }
    var selectedStyle by remember(dailyTheme.style) { mutableStateOf(dailyTheme.style) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Text(
                text = "3D Orb Style",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Large Live-preview Orb
        Box(
            modifier = Modifier.size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            LiaOrb3D(
                state = previewState,
                size = 200.dp,
                amplitude = if (previewState == NovaOrbState.SPEAKING) 0.65f else 0f,
                style = selectedStyle,
                assistantName = appState.assistantName
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("TODAY: ${dailyTheme.dayName.uppercase()} • ${dailyTheme.style.displayName.uppercase()}", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("The home orb changes automatically every day using your phone's local calendar.", color = colors.textPrimary, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))

        Spacer(modifier = Modifier.height(20.dp))

        // State toggle chips: Idle, Listening, Speaking
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                NovaOrbState.IDLE to "Idle",
                NovaOrbState.LISTENING to "Listening",
                NovaOrbState.SPEAKING to "Speaking"
            ).forEach { (stateOption, label) ->
                FilterChip(
                    selected = previewState == stateOption,
                    onClick = { previewState = stateOption },
                    label = { Text(text = label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent,
                        selectedLabelColor = colors.onAccent,
                        containerColor = colors.surfaceGlass,
                        labelColor = colors.textPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("SUNDAY TO SATURDAY AUTO CYCLE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = colors.accent, modifier = Modifier.align(Alignment.Start).padding(start = 8.dp))
        Spacer(modifier = Modifier.height(10.dp))
        (Calendar.SUNDAY..Calendar.SATURDAY).forEach { day ->
            val theme = DailyOrbThemeSchedule.forCalendarDay(day)
            val isToday = theme.dayName == dailyTheme.dayName
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (isToday) colors.surfaceRaised else colors.surfaceGlass)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(theme.dayName, color = if (isToday) colors.accent else colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(theme.style.displayName, color = colors.textPrimary, fontSize = 12.sp)
                if (isToday) Text("TODAY", color = colors.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Style carousel is a preview only; the home orb follows the automatic weekly cycle.
        Text(
            text = "PREVIEW ORB STYLES",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = colors.accent,
            modifier = Modifier.align(Alignment.Start).padding(start = 8.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NovaOrbStyle.entries.forEach { styleItem ->
                val isSelected = styleItem == selectedStyle
                val shape = RoundedCornerShape(20.dp)

                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .scale(if (isSelected) 1.05f else 0.95f)
                        .graphicsLayer {
                            cameraDistance = 12f * density
                            rotationY = if (isSelected) 0f else 15f
                        }
                        .clip(shape)
                        .background(if (isSelected) colors.surfaceRaised else colors.surfaceGlass)
                        .clickable {
                            selectedStyle = styleItem
                        }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LiaOrb3D(
                                state = previewState,
                                size = 60.dp,
                                amplitude = 0f,
                                style = styleItem
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = styleItem.displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
