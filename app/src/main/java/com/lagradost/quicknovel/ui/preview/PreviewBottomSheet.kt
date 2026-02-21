package com.lagradost.quicknovel.ui.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.ui.UiImage
import com.lagradost.quicknovel.ui.download.LibraryThemeColors
import com.lagradost.quicknovel.ui.download.NovelImage
import com.lagradost.quicknovel.ui.download.rememberLibraryThemeColors
import com.lagradost.quicknovel.ui.result.ResultViewModel
// Import the extension function properly
import com.lagradost.quicknovel.util.SettingsHelper.getRating

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewBottomSheet(
    viewModel: ResultViewModel,
    onDismiss: () -> Unit,
    onLoadResult: (url: String, apiName: String) -> Unit
) {
    val colors = rememberLibraryThemeColors()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val loadResponse by viewModel.loadResponse.observeAsState()
    val readState by viewModel.readState.observeAsState(ReadType.NONE)
    val downloadState by viewModel.downloadState.observeAsState()

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // Show full description dialog
    var showDescriptionDialog by remember { mutableStateOf(false) }
    var descriptionDialogTitle by remember { mutableStateOf("") }
    var descriptionDialogText by remember { mutableStateOf("") }

    // Bookmark dropdown
    var showBookmarkMenu by remember { mutableStateOf(false) }

    // Only show if we have a response (loading or success)
    if (loadResponse == null) return

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.clear()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = colors.outline,
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        when (val resource = loadResponse) {
            is Resource.Loading -> {
                PreviewLoadingContent(colors = colors)
            }

            is Resource.Success -> {
                val data = resource.value
                val apiName = viewModel.apiName
                val isImported = apiName == IMPORT_SOURCE || apiName == IMPORT_SOURCE_PDF
                val hasDownload = downloadState != null && (downloadState?.progress ?: 0) > 0

                // Get rating string - call extension function directly on context
                val ratingText: String? = data.rating?.let { score ->
                    context.getRating(score)
                }

                // Convert image properly - create UiImage directly without calling img()
                val imageData: UiImage? = remember(data) {
                    when (val imgData = data.downloadImage()) {
                        is UiImage -> imgData
                        else -> null
                    }
                }

                PreviewContent(
                    title = data.name,
                    synopsis = data.synopsis,
                    rating = ratingText,
                    status = data.status?.resource?.let { stringResource(it) },
                    chapterCount = if (data is StreamResponse) data.data.size else null,
                    image = imageData,
                    readState = readState,
                    isImported = isImported,
                    hasDownload = hasDownload,
                    colors = colors,
                    onPosterClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLoadResult(data.url, apiName)
                        viewModel.clear()
                        onDismiss()
                    },
                    onDeleteClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.deleteAlert()
                    },
                    onDescriptionClick = {
                        descriptionDialogTitle = data.name
                        descriptionDialogText = data.synopsis ?: ""
                        showDescriptionDialog = true
                    },
                    onBookmarkClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showBookmarkMenu = true
                    },
                    onReadMoreClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLoadResult(data.url, apiName)
                        viewModel.clear()
                        onDismiss()
                    },
                    showBookmarkMenu = showBookmarkMenu,
                    onBookmarkMenuDismiss = { showBookmarkMenu = false },
                    onBookmarkSelected = { readType ->
                        viewModel.bookmark(readType.prefValue)
                        showBookmarkMenu = false
                    }
                )
            }

            is Resource.Failure -> {
                // Will be dismissed by the observer in MainActivity
            }

            null -> {}
        }

        Spacer(modifier = Modifier.navigationBarsPadding())
    }

    // Description full dialog
    if (showDescriptionDialog) {
        AlertDialog(
            onDismissRequest = { showDescriptionDialog = false },
            title = {
                Text(
                    text = descriptionDialogTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = descriptionDialogText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showDescriptionDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textPrimary
        )
    }
}

@Composable
private fun PreviewContent(
    title: String,
    synopsis: String?,
    rating: String?,
    status: String?,
    chapterCount: Int?,
    image: UiImage?,
    readState: ReadType,
    isImported: Boolean,
    hasDownload: Boolean,
    colors: LibraryThemeColors,
    onPosterClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDescriptionClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onReadMoreClick: () -> Unit,
    showBookmarkMenu: Boolean,
    onBookmarkMenuDismiss: () -> Unit,
    onBookmarkSelected: (ReadType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Top section with poster and info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Poster
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .width(88.dp)
                    .height(138.dp)
                    .clickable(onClick = onPosterClick)
            ) {
                NovelImage(
                    image = image,
                    contentDescription = title,
                    colors = colors,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Info section
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(138.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Title row with delete button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    AnimatedVisibility(
                        visible = hasDownload,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = colors.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Metadata chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rating?.let {
                        MetadataChip(text = it, colors = colors)
                    }
                    status?.takeIf { it.isNotBlank() }?.let {
                        MetadataChip(text = it, colors = colors)
                    }
                    chapterCount?.takeIf { it > 0 }?.let {
                        MetadataChip(
                            text = "$it ${stringResource(R.string.chapter_sort)}",
                            colors = colors
                        )
                    }
                }

                // Description with fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clickable(onClick = onDescriptionClick)
                ) {
                    Text(
                        text = synopsis ?: stringResource(R.string.no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Fade gradient at bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        colors.surface
                                    )
                                )
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        if (!isImported) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Bookmark button
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = onBookmarkClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.textPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (readState == ReadType.NONE) {
                                Icons.Rounded.BookmarkBorder
                            } else {
                                Icons.Rounded.Bookmark
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(readState.stringRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Dropdown menu
                    DropdownMenu(
                        expanded = showBookmarkMenu,
                        onDismissRequest = onBookmarkMenuDismiss,
                        containerColor = colors.surface
                    ) {
                        ReadType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(type.stringRes),
                                        color = if (type == readState) colors.primary else colors.textPrimary
                                    )
                                },
                                onClick = { onBookmarkSelected(type) },
                                leadingIcon = {
                                    if (type == readState) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = colors.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // Read more button
                Button(
                    onClick = onReadMoreClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.more_info),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MetadataChip(
    text: String,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.surfaceVariant,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PreviewLoadingContent(
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    val shimmerColor = colors.surfaceVariant.copy(alpha = shimmerAlpha)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Poster skeleton
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .height(138.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(shimmerColor)
            )

            // Info skeleton
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(138.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor)
                )

                // Metadata
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerColor)
                    )
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerColor)
                    )
                }

                // Description lines
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Button skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(shimmerColor)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(shimmerColor)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}