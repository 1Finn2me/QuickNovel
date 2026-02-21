package com.lagradost.quicknovel.ui.download

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.quicknovel.DOWNLOAD_NORMAL_SORTING_METHOD
import com.lagradost.quicknovel.DOWNLOAD_SETTINGS
import com.lagradost.quicknovel.DOWNLOAD_SORTING_METHOD
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.SettingsHelper.getDownloadIsCompact
import kotlinx.coroutines.launch

// ============================================================================
// Main Library Screen
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: DownloadViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    val colors = rememberLibraryThemeColors()

    val pagesState = viewModel.pages.observeAsState()
    val pages = pagesState.value ?: emptyList()
    val currentTabState = viewModel.currentTab.observeAsState(0)
    val currentTab = currentTabState.value ?: 0

    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    var showSortingSheet by remember { mutableStateOf(false) }

    val isCompact = remember { context.getDownloadIsCompact() }
    val pullToRefreshState = rememberPullToRefreshState()
    val isOnDownloads = currentTab == 0

    // Grid columns: 3 for portrait, 6 for landscape (or 1/2 for compact)
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = remember(isLandscape, isCompact) {
        when {
            isCompact && isLandscape -> 2
            isCompact -> 1
            isLandscape -> 6
            else -> 3
        }
    }

    val tabs = remember(viewModel.readList) {
        buildList {
            add(R.string.tab_downloads)
            viewModel.readList.forEach { add(it.stringRes) }
        }
    }

    val currentPage = pages.getOrNull(currentTab)
    val filteredItems = remember(currentPage, searchQuery) {
        val items = currentPage?.items ?: emptyList()
        if (searchQuery.isBlank()) {
            items
        } else {
            items.filter { item ->
                when (item) {
                    is DownloadFragment.DownloadDataLoaded ->
                        item.name.contains(searchQuery, ignoreCase = true)
                    is ResultCached ->
                        item.name.contains(searchQuery, ignoreCase = true)
                    else -> false
                }
            }
        }
    }

    // Get counts for filter tabs
    val filterCounts = remember(pages, currentTab) {
        buildMap {
            tabs.forEachIndexed { index, _ ->
                val page = pages.getOrNull(index)
                put(index, page?.items?.size ?: 0)
            }
        }
    }

    LaunchedEffect(searchQuery) {
        viewModel.search(searchQuery)
    }

    LaunchedEffect(Unit) {
        viewModel.loadAllData(true)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (isOnDownloads) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isRefreshing = true
                    viewModel.refresh()
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
            state = pullToRefreshState,
            indicator = {
                Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = isRefreshing,
                    state = pullToRefreshState,
                    containerColor = colors.primary.copy(alpha = 0.2f),
                    color = colors.primary
                )
            }
        ) {
            when {
                pages.isEmpty() -> {
                    LibraryLoadingSkeleton(
                        gridColumns = gridColumns,
                        isCompact = isCompact,
                        colors = colors,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                filteredItems.isEmpty() -> {
                    LibraryEmptyContent(
                        searchQuery = searchQuery,
                        onQueryChange = { searchQuery = it },
                        isDownloadsPage = isOnDownloads,
                        totalCount = currentPage?.items?.size ?: 0,
                        colors = colors,
                        onImportClick = if (isOnDownloads) { { viewModel.importEpub() } } else null,
                        onSortClick = { showSortingSheet = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    LibraryContent(
                        items = filteredItems,
                        searchQuery = searchQuery,
                        onQueryChange = { searchQuery = it },
                        totalCount = currentPage?.items?.size ?: 0,
                        gridColumns = gridColumns,
                        isCompact = isCompact,
                        isDownloadsPage = isOnDownloads,
                        viewModel = viewModel,
                        colors = colors,
                        onSortClick = { showSortingSheet = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // ====== Floating filter bar with gradient - overlaid at bottom ======
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // Gradient fade effect
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                colors.background.copy(alpha = 0.8f),
                                colors.background
                            )
                        )
                    )
            )

            // Filter bar floating on top of gradient
            LibraryFilterBar(
                tabs = tabs,
                selectedTabIndex = currentTab,
                onTabSelected = { index ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.switchPage(index)
                },
                itemCounts = filterCounts,
                colors = colors,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Import FAB - only on downloads page
        if (isOnDownloads) {
            ImportFab(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.importEpub()
                },
                colors = colors,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp)
            )
        }

        // Sorting Bottom Sheet
        if (showSortingSheet) {
            SortingBottomSheet(
                isOnDownloads = isOnDownloads,
                colors = colors,
                onDismiss = { showSortingSheet = false },
                onSortSelected = {
                    viewModel.resortAllData()
                    showSortingSheet = false
                }
            )
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.loadAllData(true)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
}

// ============================================================================
// Import FAB
// ============================================================================

@Composable
private fun ImportFab(
    onClick: () -> Unit,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "fab_scale"
    )

    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = LibraryThemeColors.imported.copy(alpha = 0.3f),
                spotColor = LibraryThemeColors.imported.copy(alpha = 0.3f)
            ),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        containerColor = LibraryThemeColors.imported,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.import_epub),
            modifier = Modifier.size(26.dp)
        )
    }
}

// ============================================================================
// Library Header - Search + Sort Button
// ============================================================================

@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    totalCount: Int,
    onSortClick: () -> Unit,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LibrarySearchBar(
            query = query,
            onQueryChange = onQueryChange,
            resultCount = resultCount,
            totalCount = totalCount,
            colors = colors,
            modifier = Modifier.weight(1f)
        )

        SortButton(
            onClick = onSortClick,
            colors = colors
        )
    }
}

@Composable
private fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    totalCount: Int,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val hasQuery = query.isNotBlank()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (hasQuery) colors.primary else colors.textSecondary
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = colors.textPrimary
                ),
                singleLine = true,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_downloads),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            AnimatedVisibility(
                visible = hasQuery,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Result count badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (resultCount > 0) {
                            colors.primary.copy(alpha = 0.2f)
                        } else {
                            LibraryThemeColors.failed.copy(alpha = 0.2f)
                        }
                    ) {
                        Text(
                            text = "$resultCount",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (resultCount > 0) colors.primary else LibraryThemeColors.failed,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    // Clear button
                    Surface(
                        onClick = { onQueryChange("") },
                        shape = CircleShape,
                        color = colors.surfaceVariant
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "Clear",
                            modifier = Modifier
                                .size(32.dp)
                                .padding(6.dp),
                            tint = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortButton(
    onClick: () -> Unit,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "sort_scale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(16.dp),
        color = colors.primary.copy(alpha = 0.12f),
        tonalElevation = 2.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = Icons.Rounded.Sort,
                contentDescription = stringResource(R.string.mainpage_sort_by_button_text),
                modifier = Modifier.size(24.dp),
                tint = colors.primary
            )
        }
    }
}

// ============================================================================
// Filter Bar - Bottom tabs
// ============================================================================

@Composable
private fun LibraryFilterBar(
    tabs: List<Int>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    itemCounts: Map<Int, Int>,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        tabs.forEachIndexed { index, titleRes ->
            FilterChip(
                title = stringResource(titleRes),
                icon = getTabIcon(index),
                selected = selectedTabIndex == index,
                color = getTabColor(index, colors),
                colors = colors,
                count = itemCounts[index] ?: 0,
                showCount = selectedTabIndex == index && (itemCounts[index] ?: 0) > 0,
                onClick = { onTabSelected(index) }
            )
        }
    }
}

@Composable
private fun FilterChip(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    color: Color,
    colors: LibraryThemeColors,
    count: Int,
    showCount: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "chip_scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.15f) else colors.surface,
        animationSpec = tween(200),
        label = "chip_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) color else colors.textSecondary,
        animationSpec = tween(200),
        label = "chip_content"
    )

    val borderColor by animateColorAsState(
        targetValue = if (selected) color else Color.Transparent,
        animationSpec = tween(200),
        label = "chip_border"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = RoundedCornerShape(14.dp),
        color = backgroundColor,
        contentColor = contentColor,
        border = BorderStroke(1.5.dp, borderColor),
        shadowElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor
            )

            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1
            )

            AnimatedVisibility(
                visible = showCount,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = color.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = if (count > 999) "999+" else count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun getTabIcon(index: Int): ImageVector {
    return when (index) {
        0 -> Icons.Rounded.CloudDownload // Downloads
        1 -> Icons.Rounded.MenuBook // Reading
        2 -> Icons.Rounded.PauseCircle // On Hold
        3 -> Icons.Rounded.BookmarkAdd // Plan to Read
        4 -> Icons.Rounded.CheckCircle // Completed
        5 -> Icons.Rounded.Cancel // Dropped
        else -> Icons.Rounded.LibraryBooks
    }
}

@Composable
private fun getTabColor(index: Int, colors: LibraryThemeColors): Color {
    return when (index) {
        0 -> LibraryThemeColors.downloaded // Downloads
        1 -> LibraryThemeColors.reading // Reading
        2 -> LibraryThemeColors.onHold // On Hold
        3 -> LibraryThemeColors.planToRead // Plan to Read
        4 -> LibraryThemeColors.completed // Completed
        5 -> LibraryThemeColors.dropped // Dropped
        else -> colors.primary
    }
}

// ============================================================================
// Library Content - Grid with header
// ============================================================================

// ============================================================================
// Library Content - Grid with header
// ============================================================================

@Composable
private fun LibraryContent(
    items: List<Any>,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    totalCount: Int,
    gridColumns: Int,
    isCompact: Boolean,
    isDownloadsPage: Boolean,
    viewModel: DownloadViewModel,
    colors: LibraryThemeColors,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Bottom padding for filter bar
    val bottomPadding = 100.dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 7.dp,
            end = 7.dp,
            top = 0.dp,
            bottom = bottomPadding
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),  // Changed from 12.dp to 6.dp
        verticalArrangement = Arrangement.spacedBy(6.dp)     // Changed from 12.dp to 6.dp
    ) {
        // Header - Full span
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            LibraryHeader(
                query = searchQuery,
                onQueryChange = onQueryChange,
                resultCount = items.size,
                totalCount = totalCount,
                onSortClick = onSortClick,
                colors = colors,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Grid items
        itemsIndexed(
            items = items,
            key = { index, item ->
                when (item) {
                    is DownloadFragment.DownloadDataLoaded -> "download_${item.id}"
                    is ResultCached -> "bookmark_${item.id}"
                    else -> "item_$index"
                }
            }
        ) { _, item ->
            when (item) {
                is DownloadFragment.DownloadDataLoaded -> {
                    DownloadCard(
                        item = item,
                        isCompact = isCompact,
                        viewModel = viewModel,
                        colors = colors
                    )
                }
                is ResultCached -> {
                    BookmarkCard(
                        item = item,
                        isCompact = isCompact,
                        viewModel = viewModel,
                        colors = colors
                    )
                }
            }
        }
    }
}

// ============================================================================
// Empty Content
// ============================================================================

@Composable
private fun LibraryEmptyContent(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    isDownloadsPage: Boolean,
    totalCount: Int,
    colors: LibraryThemeColors,
    onImportClick: (() -> Unit)?,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(
            top = 8.dp,
            start = 16.dp,
            end = 16.dp
        )
    ) {
        LibraryHeader(
            query = searchQuery,
            onQueryChange = onQueryChange,
            resultCount = 0,
            totalCount = totalCount,
            onSortClick = onSortClick,
            colors = colors,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = 100.dp),
            contentAlignment = Alignment.Center
        ) {
            LibraryEmptyState(
                searchQuery = searchQuery,
                isDownloadsPage = isDownloadsPage,
                colors = colors
            )
        }
    }
}

@Composable
private fun LibraryEmptyState(
    searchQuery: String,
    isDownloadsPage: Boolean,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier.padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when {
                searchQuery.isNotBlank() -> {
                    // Search empty state
                    EmptyStateIcon(
                        icon = Icons.Outlined.SearchOff,
                        color = colors.textSecondary.copy(alpha = 0.5f),
                        backgroundColor = colors.surfaceVariant
                    )

                    EmptyStateText(
                        title = "No results found",
                        subtitle = "No novels match \"$searchQuery\"",
                        colors = colors
                    )
                }

                isDownloadsPage -> {
                    // Downloads empty state
                    EmptyStateIcon(
                        icon = Icons.Rounded.CloudDownload,
                        color = colors.primary,
                        backgroundColor = colors.primary.copy(alpha = 0.15f)
                    )

                    EmptyStateText(
                        title = "No downloads yet",
                        subtitle = "Download novels to read offline\nor import your own EPUBs",
                        colors = colors
                    )

                    EmptyStateHint(
                        icon = Icons.Rounded.Add,
                        text = "Tap + to import an EPUB",
                        color = LibraryThemeColors.imported
                    )
                }

                else -> {
                    // Bookmarks empty state
                    EmptyStateIcon(
                        icon = Icons.Rounded.BookmarkAdd,
                        color = colors.primary,
                        backgroundColor = colors.primary.copy(alpha = 0.15f)
                    )

                    EmptyStateText(
                        title = "No bookmarks yet",
                        subtitle = "Add novels to your reading list\nto see them here",
                        colors = colors
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateIcon(
    icon: ImageVector,
    color: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = backgroundColor,
        modifier = modifier.size(88.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = color
            )
        }
    }
}

@Composable
private fun EmptyStateText(
    title: String,
    subtitle: String,
    colors: LibraryThemeColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun EmptyStateHint(
    icon: ImageVector,
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.1f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = color
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ============================================================================
// Loading Skeleton
// ============================================================================

@Composable
private fun LibraryLoadingSkeleton(
    gridColumns: Int,
    isCompact: Boolean,
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 100.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        // Header skeleton - full span
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(shimmerColor)
                )
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(shimmerColor)
                )
            }
        }

        // Card skeletons
        items(8) {
            if (isCompact) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(shimmerColor)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.68f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(shimmerColor)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerColor)
                    )
                }
            }
        }
    }
}

// ============================================================================
// Sorting Bottom Sheet
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortingBottomSheet(
    isOnDownloads: Boolean,
    colors: LibraryThemeColors,
    onDismiss: () -> Unit,
    onSortSelected: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState()

    val sortingMethods = if (isOnDownloads) {
        DownloadViewModel.sortingMethods
    } else {
        DownloadViewModel.normalSortingMethods
    }

    val key = if (isOnDownloads) DOWNLOAD_SORTING_METHOD else DOWNLOAD_NORMAL_SORTING_METHOD
    var selectedId by remember {
        mutableIntStateOf(
            com.lagradost.quicknovel.BaseApplication.getKey<Int>(DOWNLOAD_SETTINGS, key) ?: DEFAULT_SORT
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Rounded.Sort,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.filter_dialog_sort_by),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }

            HorizontalDivider(color = colors.outline)

            Spacer(modifier = Modifier.height(8.dp))

            // Sort options
            sortingMethods.forEach { method ->
                val isSelected = selectedId == method.id || selectedId == method.inverse
                val isAscending = selectedId == method.id

                SortingItem(
                    method = method,
                    isSelected = isSelected,
                    isAscending = isAscending,
                    colors = colors,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val newId = if (selectedId == method.id) method.inverse else method.id
                        selectedId = newId
                        com.lagradost.quicknovel.BaseApplication.setKey(DOWNLOAD_SETTINGS, key, newId)
                        onSortSelected(newId)
                    }
                )
            }
        }
    }
}

@Composable
private fun SortingItem(
    method: SortingMethod,
    isSelected: Boolean,
    isAscending: Boolean,
    colors: LibraryThemeColors,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary.copy(alpha = 0.1f) else Color.Transparent,
        animationSpec = tween(200),
        label = "sort_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary else colors.textPrimary,
        animationSpec = tween(200),
        label = "sort_content"
    )

    Surface(
        onClick = onClick,
        color = backgroundColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val iconRotation by animateFloatAsState(
                targetValue = if (isAscending) 0f else 180f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "icon_rotation"
            )

            Icon(
                imageVector = if (method.inverse == method.id) {
                    Icons.Rounded.Check
                } else {
                    Icons.Rounded.ArrowDownward
                },
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        if (method.inverse != method.id) {
                            rotationZ = iconRotation
                        }
                    },
                tint = if (isSelected) colors.primary else colors.textSecondary
            )

            Text(
                text = stringResource(method.name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )

            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.primary,
                    modifier = Modifier.size(8.dp)
                ) {}
            }
        }
    }
}