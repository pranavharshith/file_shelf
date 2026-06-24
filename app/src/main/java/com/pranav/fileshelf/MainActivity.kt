package com.pranav.fileshelf

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.pranav.fileshelf.data.FileShelfRepository
import com.pranav.fileshelf.data.PendingCopy
import com.pranav.fileshelf.data.StagedFile
import com.pranav.fileshelf.service.OverlayService
import com.pranav.fileshelf.util.MimeIconResolver
import com.pranav.fileshelf.util.PermissionHelper
import com.pranav.fileshelf.util.ShareIntentHelper
import com.pranav.fileshelf.util.formatFileSize
import kotlinx.coroutines.launch

private val Background = Color(0xFFF2F2F7)
private val Surface = Color(0xFFFFFFFF)
private val Tint = Color(0xFF007AFF)
private val TextPrimary = Color(0xFF1C1C1E)
private val TextSecondary = Color(0x993C3C43)
private val Separator = Color(0x493C3C43)
private val Destructive = Color(0xFFFF3B30)

private val AppColorScheme = lightColorScheme(
    primary = Tint,
    onPrimary = Color.White,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Destructive,
)

private fun chipColor(mimeType: String, displayName: String = ""): Color {
    chipColorFromMime(mimeType)?.let { return it }
    chipColorFromExtension(displayName.substringAfterLast('.', "").lowercase())?.let { return it }
    return Color(0x268E8E93)
}

private fun chipColorFromMime(mimeType: String): Color? = when {
    mimeType.contains("pdf") -> Color(0x26FF3B30)
    mimeType.startsWith("image/") -> Color(0x2634C759)
    mimeType.startsWith("video/") -> Color(0x26FF9500)
    mimeType.startsWith("audio/") -> Color(0x26AF52DE)
    mimeType.contains("word") || mimeType.contains("document") ||
        mimeType.contains("sheet") || mimeType.contains("excel") ||
        mimeType.contains("presentation") || mimeType.contains("powerpoint") ||
        mimeType.startsWith("text/") -> Color(0x26007AFF)
    else -> null
}

private fun chipColorFromExtension(ext: String): Color? = when (ext) {
    "pdf" -> Color(0x26FF3B30)
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tiff", "tif" -> Color(0x2634C759)
    "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "flv", "wmv" -> Color(0x26FF9500)
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma" -> Color(0x26AF52DE)
    "doc", "docx", "odt", "rtf", "pages",
    "xls", "xlsx", "ods", "csv", "numbers",
    "ppt", "pptx", "odp", "key",
    "txt", "md", "log", "json", "xml", "yml", "yaml", "ini", "conf" -> Color(0x26007AFF)
    else -> null
}

// ── Category filtering ────────────────────────────────────────────────────────

private enum class FileCategory(val displayName: String) {
    ALL("All"),
    IMAGES("Images"),
    VIDEOS("Videos"),
    PDFS("PDFs"),
    DOCS("Docs"),
    AUDIO("Audio"),
    ARCHIVES("Archives"),
    TEXT("Text"),
    OTHER("Other")
}

private fun categorizeFile(mimeType: String, displayName: String = ""): FileCategory {
    categorizeFromMime(mimeType)?.let { return it }
    categorizeFromExtension(displayName.substringAfterLast('.', "").lowercase())?.let { return it }
    return FileCategory.OTHER
}

private fun categorizeFromMime(mimeType: String): FileCategory? = when {
    mimeType.startsWith("image/")                                          -> FileCategory.IMAGES
    mimeType.startsWith("video/")                                          -> FileCategory.VIDEOS
    mimeType.contains("pdf")                                               -> FileCategory.PDFS
    mimeType.contains("word") || mimeType.contains("document") ||
        mimeType.contains("sheet") || mimeType.contains("excel") ||
        mimeType.contains("presentation") || mimeType.contains("powerpoint") -> FileCategory.DOCS
    mimeType.startsWith("audio/")                                          -> FileCategory.AUDIO
    mimeType.contains("zip")  || mimeType.contains("archive") ||
        mimeType.contains("tar") || mimeType.contains("rar") ||
        mimeType.contains("compressed") || mimeType.contains("7z")        -> FileCategory.ARCHIVES
    mimeType.startsWith("text/")                                           -> FileCategory.TEXT
    else                                                                   -> null
}

private fun categorizeFromExtension(ext: String): FileCategory? = when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tiff", "tif" -> FileCategory.IMAGES
    "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "flv", "wmv" -> FileCategory.VIDEOS
    "pdf" -> FileCategory.PDFS
    "doc", "docx", "odt", "rtf", "pages",
    "xls", "xlsx", "ods", "csv", "numbers",
    "ppt", "pptx", "odp", "key" -> FileCategory.DOCS
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma" -> FileCategory.AUDIO
    "zip", "tar", "gz", "tgz", "bz2", "xz", "rar", "7z" -> FileCategory.ARCHIVES
    "txt", "md", "log", "json", "xml", "yml", "yaml", "ini", "conf" -> FileCategory.TEXT
    else -> null
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                FileShelfAppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            FileShelfRepository.refresh(this@MainActivity)
        }
    }
}

@Composable
private fun FileShelfAppRoot() {
    val context = LocalContext.current
    var onboardingStep by remember {
        mutableStateOf(
            if (PermissionHelper.isOnboardingComplete(context)) ONBOARDING_DONE else 0
        )
    }

    when {
        onboardingStep < ONBOARDING_DONE -> OnboardingFlow(
            step = onboardingStep,
            onStepChange = { onboardingStep = it },
            onComplete = {
                PermissionHelper.setOnboardingComplete(context)
                onboardingStep = ONBOARDING_DONE
            }
        )
        else -> ShelfScreen(
            onRerunSetup = {
                PermissionHelper.clearOnboarding(context)
                onboardingStep = 0
            }
        )
    }
}

/**
 * Shows a confirmation dialog before clearing all files.
 * Prevents accidental data loss.
 */
private fun showClearAllConfirmation(context: android.content.Context, onResult: (Boolean) -> Unit) {
    val dialog = android.app.AlertDialog.Builder(context)
        .setTitle("Clear All Files?")
        .setMessage("This will remove all ${FileShelfRepository.files.value.size} staged files. This action cannot be undone.")
        .setPositiveButton("Clear All") { _, _ -> onResult(true) }
        .setNegativeButton("Cancel") { _, _ -> onResult(false) }
        .setCancelable(true)
        .setOnCancelListener { onResult(false) }
        .create()
    
    dialog.show()
}

private val onboardingSteps = listOf(
    Triple("FS", "Welcome to File Shelf",
        "Stage files from any app and share them anywhere without losing your place. Files auto-expire after 24 hours."),
    Triple("OV", "Display Over Other Apps",
        "The floating bubble needs permission to appear on top of other apps while you multitask."),
    Triple("NT", "Notifications",
        "A small notification keeps the floating shelf visible while files are staged."),
    Triple("OK", "Enable Floating Shelf",
        "File Shelf runs a lightweight service to keep the bubble visible while you move between apps. " +
            "It stops the moment you clear your files.")
)

@Composable
private fun OnboardingFlow(
    step: Int,
    onStepChange: (Int) -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onStepChange(step + 1) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStepChange(step + 1) }

    val (icon, title, body) = onboardingSteps[step.coerceIn(0, 3)]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OnboardingProgressDots(currentStep = step)

            Text(text = icon, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            Text(
                text = title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                lineHeight = 32.sp
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = body,
                fontSize = 15.sp,
                color = TextSecondary,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(40.dp))

            OnboardingActionButton(
                step = step,
                context = context,
                overlayLauncher = overlayLauncher,
                notificationLauncher = notificationLauncher,
                onStepChange = onStepChange,
                onComplete = onComplete
            )

            OnboardingSkipButton(step = step, onStepChange = onStepChange, onComplete = onComplete, context = context)
        }
    }
}

@Composable
private fun OnboardingProgressDots(currentStep: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 48.dp)
    ) {
        repeat(ONBOARDING_DONE) { index ->
            Box(
                modifier = Modifier
                    .size(
                        if (index == currentStep) 20.dp else 6.dp,
                        6.dp
                    )
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (index == currentStep) Tint else Separator
                    )
            )
        }
    }
}

@Composable
private fun OnboardingActionButton(
    step: Int,
    context: android.content.Context,
    overlayLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    notificationLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    onStepChange: (Int) -> Unit,
    onComplete: () -> Unit
) {
    Button(
        onClick = {
            when (step) {
                0 -> onStepChange(1)
                1 -> overlayLauncher.launch(
                    PermissionHelper.overlaySettingsIntent(context)
                )
                2 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    } else {
                        onStepChange(3)
                    }
                }
                3 -> {
                    PermissionHelper.setFloatingShelfEnabled(context, true)
                    onComplete()
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tint)
    ) {
        Text(
            text = when (step) {
                3 -> "Enable Floating Shelf"
                0 -> "Get Started"
                else -> "Grant Permission"
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun OnboardingSkipButton(
    step: Int,
    onStepChange: (Int) -> Unit,
    onComplete: () -> Unit,
    context: android.content.Context
) {
    if (step in 1..2) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onStepChange(step + 1) }) {
            Text("Skip for now", color = TextSecondary, fontSize = 14.sp)
        }
    }
    if (step == 3) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = {
            PermissionHelper.setFloatingShelfEnabled(context, false)
            onComplete()
        }) {
            Text("Skip for now", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfScreen(onRerunSetup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by FileShelfRepository.files.collectAsState()
    val pending by FileShelfRepository.pendingCopies.collectAsState()
    
    // Help sheet state
    var showHelpSheet by remember { mutableStateOf(false) }
    
    // Check if user has opened help before
    val hasOpenedHelp = remember {
        context.getSharedPreferences(
            "file_shelf_prefs",
            android.content.Context.MODE_PRIVATE
        ).getBoolean("has_opened_help", false)
    }
    
    val isBubbleActive by OverlayService.isRunningFlow.collectAsState()
    val hasFiles = files.isNotEmpty()

    // ── Category filter state
    var selectedCategory by remember { mutableStateOf(FileCategory.ALL) }

    LaunchedEffect(files) {
        if (selectedCategory != FileCategory.ALL &&
            files.none { categorizeFile(it.mimeType, it.displayName) == selectedCategory }
        ) {
            selectedCategory = FileCategory.ALL
        }
    }

    val filteredFiles = remember(files, selectedCategory) {
        if (selectedCategory == FileCategory.ALL) files
        else files.filter { categorizeFile(it.mimeType, it.displayName) == selectedCategory }
    }

    LaunchedEffect(Unit) { FileShelfRepository.refresh(context) }
    
    if (showHelpSheet) {
        HelpCenterSheet(
            onDismiss = {
                showHelpSheet = false
                context.getSharedPreferences(
                    "file_shelf_prefs",
                    android.content.Context.MODE_PRIVATE
                ).edit().putBoolean("has_opened_help", true).apply()
            },
            onRerunSetup = onRerunSetup
        )
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            ShelfTopBar(
                hasOpenedHelp = hasOpenedHelp,
                onHelpClick = { showHelpSheet = true },
                isBubbleActive = isBubbleActive,
                hasFiles = hasFiles,
                context = context,
                files = files,
                scope = scope
            )
        }
    ) { padding ->
        ShelfContent(
            files = files,
            pending = pending,
            filteredFiles = filteredFiles,
            selectedCategory = selectedCategory,
            onSelectCategory = { selectedCategory = it },
            padding = padding,
            scope = scope,
            context = context
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfTopBar(
    hasOpenedHelp: Boolean,
    onHelpClick: () -> Unit,
    isBubbleActive: Boolean,
    hasFiles: Boolean,
    context: android.content.Context,
    files: List<StagedFile>,
    scope: kotlinx.coroutines.CoroutineScope
) {
    TopAppBar(
        title = {
            Text(
                "File Shelf",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextPrimary
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
        actions = {
            HelpIconButton(hasOpenedHelp = hasOpenedHelp, onClick = onHelpClick)
            Spacer(Modifier.width(4.dp))
            BubbleToggleButton(
                isBubbleActive = isBubbleActive,
                hasFiles = hasFiles,
                context = context
            )
            if (files.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    scope.launch {
                        showClearAllConfirmation(context) { confirmed ->
                            if (confirmed) {
                                scope.launch {
                                    FileShelfRepository.clearAll(context)
                                    OverlayService.stop(context)
                                }
                            }
                        }
                    }
                }) {
                    Text(
                        "Clear all",
                        color = Destructive,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    )
}

@Composable
private fun HelpIconButton(hasOpenedHelp: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(end = 4.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (!hasOpenedHelp) Color(0x1A007AFF)
                    else Color(0x0A007AFF)
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Help",
                tint = Tint,
                modifier = Modifier.size(22.dp)
            )
        }
        if (!hasOpenedHelp) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Tint)
            )
        }
    }
}

@Composable
private fun BubbleToggleButton(
    isBubbleActive: Boolean,
    hasFiles: Boolean,
    context: android.content.Context
) {
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isBubbleActive) Tint.copy(alpha = 0.12f)
                else if (hasFiles) Color(0x0A000000)
                else Color(0x05000000)
            )
            .clickable(enabled = hasFiles) {
                if (isBubbleActive) {
                    OverlayService.stop(context)
                } else {
                    OverlayService.start(context)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.BubbleChart,
            contentDescription = if (isBubbleActive) "Hide Bubble"
                else "Show Bubble",
            tint = when {
                isBubbleActive -> Tint
                hasFiles -> TextPrimary
                else -> TextSecondary.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
@Suppress("detekt:LongParameterList")
private fun ShelfContent(
    files: List<StagedFile>,
    pending: List<PendingCopy>,
    filteredFiles: List<StagedFile>,
    selectedCategory: FileCategory,
    onSelectCategory: (FileCategory) -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
    if (files.isEmpty() && pending.isEmpty()) {
        ShelfEmptyState(padding = padding)
    } else {
        ShelfFileList(
            files = files,
            pending = pending,
            filteredFiles = filteredFiles,
            selectedCategory = selectedCategory,
            onSelectCategory = onSelectCategory,
            padding = padding,
            scope = scope,
            context = context
        )
    }
}

@Composable
private fun ShelfEmptyState(
    padding: androidx.compose.foundation.layout.PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FS", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your shelf is empty",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Share a file from any app and choose File Shelf",
            fontSize = 14.sp,
            color = TextSecondary
        )
    }
}

@Composable
@Suppress("detekt:LongParameterList")
private fun ShelfFileList(
    files: List<StagedFile>,
    pending: List<PendingCopy>,
    filteredFiles: List<StagedFile>,
    selectedCategory: FileCategory,
    onSelectCategory: (FileCategory) -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        CategoryFilterStrip(
            files = files,
            selected = selectedCategory,
            onSelect = onSelectCategory
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(pending, key = { it.id }) { copy ->
                PendingCopyCard(copy)
            }
            if (filteredFiles.isEmpty() && selectedCategory != FileCategory.ALL) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No ${selectedCategory.displayName.lowercase()} files",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                items(filteredFiles, key = { it.id }) { file ->
                    StagedFileCard(
                        file = file,
                        onShare = {
                            ShareIntentHelper.launchChooser(context, file)
                        },
                        onRemove = {
                            scope.launch {
                                FileShelfRepository.remove(context, file.id)
                                if (FileShelfRepository.files.value.isEmpty()) {
                                    OverlayService.stop(context)
                                } else {
                                    OverlayService.refreshBubble(context)
                                }
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PendingCopyCard(copy: PendingCopy) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Tint,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(copy.displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
                Text("Copying...", fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun StagedFileCard(
    file: StagedFile,
    onShare: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onShare),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(chipColor(file.mimeType, file.displayName)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = MimeIconResolver.emojiFor(file.mimeType, file.displayName),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(file.sizeBytes),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            IconButton(
                onClick = onShare,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x1A007AFF))
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x1AFF3B30))
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = Destructive,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Category filter composables ───────────────────────────────────────────────

@Composable
private fun CategoryFilterStrip(
    files: List<StagedFile>,
    selected: FileCategory,
    onSelect: (FileCategory) -> Unit
) {
    // Build ordered list of (category → count) for categories that have files
    val categoryCounts = remember(files) {
        val counts = mutableMapOf<FileCategory, Int>()
        files.forEach {
            val cat = categorizeFile(it.mimeType, it.displayName)
            counts[cat] = (counts[cat] ?: 0) + 1
        }
        FileCategory.entries
            .filter { it != FileCategory.ALL && counts.containsKey(it) }
            .map { it to counts.getValue(it) }
    }

    // Strip is invisible when no files are present (no gap rendered)
    if (categoryCounts.isEmpty()) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item(key = "all") {
            FilterPill(
                label = "All",
                count = files.size,
                selected = selected == FileCategory.ALL,
                onClick = { onSelect(FileCategory.ALL) }
            )
        }
        items(categoryCounts, key = { (cat, _) -> cat.name }) { (category, count) ->
            FilterPill(
                label = category.displayName,
                count = count,
                selected = selected == category,
                onClick = { onSelect(category) }
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Tint else Surface)
            .then(
                if (!selected) Modifier.border(
                    width = 1.dp,
                    color = Color(0x1A000000),
                    shape = RoundedCornerShape(17.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Color.White else TextPrimary,
                lineHeight = 13.sp
            )
            Text(
                text = "$count",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = if (selected) Color.White.copy(alpha = 0.65f) else TextSecondary,
                lineHeight = 11.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private const val ONBOARDING_DONE = 4
