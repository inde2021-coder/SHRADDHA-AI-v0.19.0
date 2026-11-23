package com.Lia.assistant.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Lia.assistant.license.AccessKeyManager
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.depthSurface

@Composable
fun AccessibilitySettingsRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .depthSurface(shape = shape, elevation = 3.dp, rimColor = colors.rimLight)
            .clip(shape)
            .background(colors.surfaceGlass)
            .clickable {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Shraddha AI — Device Control",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Enable this service for voice app control, screen reading, taps, typing and gestures. A separate optional service is only for WhatsApp send verification.",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
        }
        Text(
            text = "Configure",
            fontSize = 13.sp,
            color = colors.accent,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun AccessKeyBanner(onActivateClick: () -> Unit, modifier: Modifier = Modifier) {
    val info by AccessKeyManager.info.collectAsState()
    val colors = LocalNovaColors.current
    if (!info.active || info.blocked) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .depthSurface(shape = shape, elevation = 3.dp, rimColor = colors.rimLight)
                .clip(shape)
                .background(colors.surfaceGlass)
                .clickable { onActivateClick() }
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (info.blocked) "Access Key Blocked - Tap to resolve" else "Access Key Required - Tap to activate",
                    color = if (info.blocked) colors.error else colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Activate",
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AccessKeySection(modifier: Modifier = Modifier) {
    val info by AccessKeyManager.info.collectAsState()
    val colors = LocalNovaColors.current
    NovaGlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        elevation = 3.dp
    ) {
        Column {
            Text(
                text = "License & Access Key",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Plan: ${info.plan} (${info.statusText})",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
        }
    }
}
