package com.pranav.fileshelf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFFF2F2F7)
private val Surface = Color(0xFFFFFFFF)
private val Tint = Color(0xFF007AFF)
private val TextPrimary = Color(0xFF1C1C1E)
private val TextSecondary = Color(0x993C3C43)
private val Separator = Color(0x1F3C3C43)

/**
 * iOS-polished Help Center bottom sheet.
 * Features collapsible sections with smooth animations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpCenterSheet(
    onDismiss: () -> Unit,
    onRerunSetup: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Background,
        tonalElevation = 0.dp,
        dragHandle = { HelpSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.help_center_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            
            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HelpGettingStartedSection()
                HelpBubbleSection()
                HelpFilesSection()
                HelpSettingsSection(onDismiss = onDismiss, onRerunSetup = onRerunSetup)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HelpSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(Color(0x33000000))
        )
    }
}

@Composable
private fun HelpGettingStartedSection() {
    HelpSection(
        title = stringResource(R.string.help_section_getting_started),
        icon = "📚"
    ) {
        HelpItem(
            title = stringResource(R.string.help_getting_started_title),
            body = stringResource(R.string.help_getting_started_body)
        )
    }
}

@Composable
private fun HelpBubbleSection() {
    HelpSection(
        title = stringResource(R.string.help_section_bubble),
        icon = "🫧"
    ) {
        HelpItem(
            title = stringResource(R.string.help_bubble_title),
            body = stringResource(R.string.help_bubble_body)
        )
    }
}

@Composable
private fun HelpFilesSection() {
    HelpSection(
        title = stringResource(R.string.help_section_files),
        icon = "📦"
    ) {
        HelpItem(
            title = stringResource(R.string.help_files_title_share),
            body = stringResource(R.string.help_files_share_body)
        )
        Spacer(Modifier.height(12.dp))
        HelpItem(
            title = stringResource(R.string.help_files_title_drag),
            body = stringResource(R.string.help_files_drag_body)
        )
        Spacer(Modifier.height(12.dp))
        HelpItem(
            title = stringResource(R.string.help_files_title_selection),
            body = stringResource(R.string.help_files_selection_body)
        )
        Spacer(Modifier.height(12.dp))
        HelpItem(
            title = stringResource(R.string.help_files_title_remove),
            body = stringResource(R.string.help_files_remove_body)
        )
    }
}

@Composable
private fun HelpSettingsSection(onDismiss: () -> Unit, onRerunSetup: () -> Unit) {
    HelpSection(
        title = stringResource(R.string.help_section_settings),
        icon = "⚙️"
    ) {
        HelpItem(
            title = stringResource(R.string.help_settings_title_expiry),
            body = stringResource(R.string.help_settings_expiry_body)
        )
        Spacer(Modifier.height(12.dp))
        HelpItem(
            title = stringResource(R.string.help_settings_title_permissions),
            body = stringResource(R.string.help_settings_permissions_body)
        )
        Spacer(Modifier.height(12.dp))
        HelpRerunSetupBlock(onDismiss = onDismiss, onRerunSetup = onRerunSetup)
    }
}

@Composable
private fun HelpRerunSetupBlock(onDismiss: () -> Unit, onRerunSetup: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.help_settings_title_rerun),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = stringResource(R.string.help_settings_rerun_body),
            fontSize = 14.sp,
            color = TextSecondary,
            lineHeight = 20.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Button(
            onClick = {
                onDismiss()
                onRerunSetup()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Tint)
        ) {
            Text(
                text = stringResource(R.string.help_rerun_setup_button),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Collapsible section with iOS-style accordion animation
 */
@Composable
private fun HelpSection(
    title: String,
    icon: String,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(300),
        label = "chevron_rotation"
    )
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
    ) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 20.sp,
                modifier = Modifier.padding(end = 10.dp)
            )
            
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation)
            )
        }
        
        // Section Content
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(300),
                expandFrom = Alignment.Top
            ) + fadeIn(animationSpec = tween(300)),
            exit = shrinkVertically(
                animationSpec = tween(300),
                shrinkTowards = Alignment.Top
            ) + fadeOut(animationSpec = tween(300))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Separator)
                        .padding(bottom = 12.dp)
                )
                
                Spacer(Modifier.height(12.dp))
                
                content()
            }
        }
    }
}

/**
 * Individual help item with title and body text
 */
@Composable
private fun HelpItem(
    title: String,
    body: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = body,
            fontSize = 14.sp,
            color = TextSecondary,
            lineHeight = 20.sp
        )
    }
}
