package com.lagradost.quicknovel.ui.download

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BookDownloader2.preloadPartialImportedPdf
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.DOWNLOAD_EPUB_SIZE
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.UiImage
import com.lagradost.quicknovel.util.ResultCached

// ============================================================================
// Design Tokens - Adjusted for 1.3x larger cards
// ============================================================================

private object CardTokens {
    // Card shape
    val CardCornerRadius = 14.dp  // Slightly smaller corner radius for larger cards
    val CardShape = RoundedCornerShape(CardCornerRadius)

    // Aspect ratio (2:3 like book covers)
    val AspectRatio = 0.667f  // 2/3 ratio, standard book cover

    // Badge sizing
    val BadgeCornerRadius = 8.dp
    val BadgeShape = RoundedCornerShape(BadgeCornerRadius)

    // Internal padding - slightly increased for larger cards
    val BadgePadding = 10.dp
    val ContentPadding = 10.dp

    // Gradient overlay height
    val GradientHeight = 90.dp

    // Text sizing for larger cards
    val TitleFontSize = 12.sp
    val TitleLineHeight = 14.sp
}

// ============================================================================
// Novel Image Component
// ============================================================================

@Composable
fun NovelImage(
    image: UiImage?,
    contentDescription: String?,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (image) {
            is UiImage.Image -> {
                AsyncImage(
                    model = image.url,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is UiImage.Bitmap -> {
                Image(
                    bitmap = image.bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is UiImage.Drawable -> {
                AsyncImage(
                    model = image.resId,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            null -> {
                Icon(
                    imageVector = Icons.Rounded.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),  // Larger icon for bigger cards
                    tint = colors.textSecondary.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ============================================================================
// Generating Indicator
// ============================================================================

@Composable
private fun GeneratingIndicator(
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "generating")

    val offsetX by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.primary.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.3f)
                .offset(x = (offsetX * 200).dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            colors.primary,
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}

// ============================================================================
// Download Card
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCard(
    item: DownloadFragment.DownloadDataLoaded,
    isCompact: Boolean,
    viewModel: DownloadViewModel,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val epubSize = remember(item.id, item.downloadedCount, item.lastReadTimestamp) {
        getKey<Int>(DOWNLOAD_EPUB_SIZE, item.id.toString()) ?: 0
    }

    val newChapterCount = (item.downloadedCount - epubSize).coerceAtLeast(0)
    val hasNewChapters = newChapterCount > 0 && item.state != DownloadState.IsPending && !item.isImported
    val isAPdfDownloading = item.apiName == IMPORT_SOURCE_PDF && (item.downloadedTotal != item.downloadedCount)

    val progress = if (item.downloadedTotal > 0) {
        item.downloadedCount.toFloat() / item.downloadedTotal.toFloat()
    } else 0f

    val showGenerating = item.generating

    if (isCompact) {
        DownloadCardCompact(
            item = item,
            hasNewChapters = hasNewChapters,
            newChapterCount = newChapterCount,
            progress = progress,
            showGenerating = showGenerating,
            colors = colors,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (item.apiName == IMPORT_SOURCE_PDF && item.downloadedCount < item.downloadedTotal) {
                    preloadPartialImportedPdf(item, context)
                }
                viewModel.readEpub(item)
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.showMetadata(item)
            },
            onActionClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                when (item.state) {
                    DownloadState.IsDownloading -> viewModel.pause(item)
                    DownloadState.IsPaused -> viewModel.resume(item)
                    DownloadState.IsPending -> {}
                    else -> viewModel.refreshCard(item)
                }
            },
            modifier = modifier
        )
    } else {
        DownloadCardGrid(
            item = item,
            hasNewChapters = hasNewChapters,
            newChapterCount = newChapterCount,
            isAPdfDownloading = isAPdfDownloading,
            showGenerating = showGenerating,
            colors = colors,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (item.apiName == IMPORT_SOURCE_PDF && item.downloadedCount < item.downloadedTotal) {
                    preloadPartialImportedPdf(item, context)
                }
                viewModel.readEpub(item)
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.showMetadata(item)
            },
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadCardGrid(
    item: DownloadFragment.DownloadDataLoaded,
    hasNewChapters: Boolean,
    newChapterCount: Long,
    isAPdfDownloading: Boolean,
    showGenerating: Boolean,
    colors: LibraryThemeColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)  // Increased spacing
    ) {
        Card(
            shape = CardTokens.CardShape,
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardTokens.AspectRatio)  // Using 2:3 ratio
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NovelImage(
                    image = item.image,
                    contentDescription = item.name,
                    colors = colors,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (isAPdfDownloading) 0.6f else 1f
                        }
                )

                // Gradient overlay - taller for larger cards
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CardTokens.GradientHeight)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.75f)
                                )
                            )
                        )
                )

                // New chapters badge
                if (hasNewChapters) {
                    Surface(
                        shape = CardTokens.BadgeShape,
                        color = LibraryThemeColors.newChapters,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .padding(CardTokens.BadgePadding)
                            .align(Alignment.TopStart)
                    ) {
                        Text(
                            text = "+$newChapterCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 11.sp,  // Slightly larger text
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Imported badge
                if (item.isImported) {
                    Surface(
                        shape = CardTokens.BadgeShape,
                        color = LibraryThemeColors.imported,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .padding(CardTokens.BadgePadding)
                            .align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileOpen,
                            contentDescription = "Imported",
                            modifier = Modifier
                                .size(26.dp)
                                .padding(5.dp),
                            tint = Color.White
                        )
                    }
                }

                // Loading indicator
                if (item.state == DownloadState.IsPending || isAPdfDownloading) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(52.dp)  // Larger loading indicator
                            .align(Alignment.Center)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                }

                // Download state indicator
                if (!item.isImported && item.state != DownloadState.Nothing && item.state != DownloadState.IsDone && !showGenerating) {
                    DownloadStateIndicator(
                        state = item.state,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(CardTokens.BadgePadding)
                    )
                }

                // Generating indicator
                if (showGenerating) {
                    GeneratingIndicator(
                        colors = colors,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        // Title text - larger and more readable
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            fontSize = CardTokens.TitleFontSize,
            lineHeight = CardTokens.TitleLineHeight,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadCardCompact(
    item: DownloadFragment.DownloadDataLoaded,
    hasNewChapters: Boolean,
    newChapterCount: Long,
    progress: Float,
    showGenerating: Boolean,
    colors: LibraryThemeColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showProgress = !item.isImported ||
            (item.apiName == IMPORT_SOURCE_PDF && item.downloadedTotal != item.downloadedCount)

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300),
        label = "progress"
    )

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)  // Slightly taller compact card
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Cover - wider for better visibility
                Box(modifier = Modifier.width(82.dp).fillMaxHeight()) {
                    NovelImage(
                        image = item.image,
                        contentDescription = item.name,
                        colors = colors,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (hasNewChapters) {
                        Surface(
                            shape = RoundedCornerShape(bottomEnd = 8.dp),
                            color = LibraryThemeColors.newChapters,
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Text(
                                text = "+$newChapterCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                fontSize = 10.sp
                            )
                        }
                    }

                    if (showGenerating) {
                        GeneratingIndicator(
                            colors = colors,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }

                // Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            if (item.isImported) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = LibraryThemeColors.imported.copy(alpha = 0.2f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FileOpen,
                                        contentDescription = "Imported",
                                        modifier = Modifier.size(22.dp).padding(3.dp),
                                        tint = LibraryThemeColors.imported
                                    )
                                }
                            }
                        }

                        Text(
                            text = when {
                                showGenerating -> "Generating EPUB..."
                                else -> buildString {
                                    append("${item.downloadedCount}/${item.downloadedTotal}")
                                    if (item.ETA.isNotEmpty()) append(" • ${item.ETA}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (showGenerating) colors.primary else colors.textSecondary,
                            maxLines = 1
                        )
                    }

                    // Progress bar
                    if (showProgress && item.downloadedCount < item.downloadedTotal && !showGenerating) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = getStateColor(item.state),
                            trackColor = colors.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                    } else if (showGenerating) {
                        GeneratingIndicator(colors = colors, modifier = Modifier.fillMaxWidth())
                    }
                }

                // Action button
                if (showProgress && !showGenerating) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxHeight().padding(end = 8.dp)
                    ) {
                        when (item.state) {
                            DownloadState.IsPending -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(30.dp),
                                    color = colors.textSecondary,
                                    strokeWidth = 2.dp
                                )
                            }
                            else -> {
                                IconButton(onClick = onActionClick) {
                                    Icon(
                                        imageVector = when (item.state) {
                                            DownloadState.IsDownloading -> Icons.Rounded.Pause
                                            DownloadState.IsPaused -> Icons.Rounded.PlayArrow
                                            DownloadState.IsDone -> Icons.Rounded.Check
                                            DownloadState.IsFailed -> Icons.Rounded.Refresh
                                            else -> Icons.Rounded.Refresh
                                        },
                                        contentDescription = null,
                                        tint = getStateColor(item.state),
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadStateIndicator(
    state: DownloadState,
    modifier: Modifier = Modifier
) {
    val color = getStateColor(state)
    val icon = when (state) {
        DownloadState.IsDownloading -> Icons.Rounded.Download
        DownloadState.IsPaused -> Icons.Rounded.Pause
        DownloadState.IsFailed -> Icons.Rounded.Error
        DownloadState.IsPending -> Icons.Rounded.Schedule
        else -> return
    }

    Surface(
        shape = CircleShape,
        color = color,
        shadowElevation = 4.dp,
        modifier = modifier.size(30.dp)  // Slightly larger
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(5.dp),
            tint = Color.White
        )
    }
}

@Composable
private fun getStateColor(state: DownloadState): Color {
    return when (state) {
        DownloadState.IsDownloading -> LibraryThemeColors.downloading
        DownloadState.IsPaused -> LibraryThemeColors.paused
        DownloadState.IsFailed -> LibraryThemeColors.failed
        DownloadState.IsPending -> LibraryThemeColors.pending
        DownloadState.IsDone -> LibraryThemeColors.completed
        else -> Color.Gray
    }
}

// ============================================================================
// Bookmark Card
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarkCard(
    item: ResultCached,
    isCompact: Boolean,
    viewModel: DownloadViewModel,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    if (isCompact) {
        BookmarkCardCompact(
            item = item,
            colors = colors,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.load(item)
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.showMetadata(item)
            },
            onPlayClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.stream(item)
            },
            onDeleteClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.deleteAlert(item)
            },
            modifier = modifier
        )
    } else {
        BookmarkCardGrid(
            item = item,
            colors = colors,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.load(item)
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.showMetadata(item)
            },
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkCardGrid(
    item: ResultCached,
    colors: LibraryThemeColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            shape = CardTokens.CardShape,
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardTokens.AspectRatio)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NovelImage(
                    image = item.image,
                    contentDescription = item.name,
                    colors = colors,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CardTokens.GradientHeight)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.75f)
                                )
                            )
                        )
                )

                Surface(
                    shape = CardTokens.BadgeShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(CardTokens.BadgePadding)
                ) {
                    Text(
                        text = "${item.totalChapters} ch",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            fontSize = CardTokens.TitleFontSize,
            lineHeight = CardTokens.TitleLineHeight,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkCardCompact(
    item: ResultCached,
    colors: LibraryThemeColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            NovelImage(
                image = item.image,
                contentDescription = item.name,
                colors = colors,
                modifier = Modifier.width(82.dp).fillMaxHeight()
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${item.totalChapters} ${stringResource(R.string.read_action_chapters)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }

            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxHeight().padding(end = 4.dp)
            ) {
                IconButton(onClick = onPlayClick) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.stream_read),
                        tint = colors.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// Import Card
// ============================================================================

@Composable
fun ImportCard(
    isCompact: Boolean,
    colors: LibraryThemeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    if (isCompact) {
        Card(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = LibraryThemeColors.imported.copy(alpha = 0.1f)
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = SolidColor(LibraryThemeColors.imported.copy(alpha = 0.3f))
            ),
            modifier = modifier.fillMaxWidth().height(65.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = LibraryThemeColors.imported.copy(alpha = 0.2f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        tint = LibraryThemeColors.imported
                    )
                }

                Text(
                    text = stringResource(R.string.import_epub),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = LibraryThemeColors.imported
                )
            }
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
                shape = CardTokens.CardShape,
                colors = CardDefaults.cardColors(
                    containerColor = LibraryThemeColors.imported.copy(alpha = 0.1f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = SolidColor(LibraryThemeColors.imported.copy(alpha = 0.3f))
                ),
                modifier = Modifier.fillMaxWidth().aspectRatio(CardTokens.AspectRatio)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = LibraryThemeColors.imported.copy(alpha = 0.2f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().padding(14.dp),
                                tint = LibraryThemeColors.imported
                            )
                        }

                        Text(
                            text = "Import",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = LibraryThemeColors.imported,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.import_epub),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                fontSize = CardTokens.TitleFontSize,
                maxLines = 2,
                minLines = 2,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}