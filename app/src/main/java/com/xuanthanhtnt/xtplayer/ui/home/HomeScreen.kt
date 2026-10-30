package com.xuanthanhtnt.xtplayer.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.BuildConfig
import com.xuanthanhtnt.xtplayer.R
import com.xuanthanhtnt.xtplayer.core.TimeFormat
import com.xuanthanhtnt.xtplayer.data.LibraryIndex
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.MediaEntry
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.ui.ActionsDialog
import com.xuanthanhtnt.xtplayer.ui.ArtworkImage
import com.xuanthanhtnt.xtplayer.ui.LandscapeCard
import com.xuanthanhtnt.xtplayer.ui.LibraryActions
import com.xuanthanhtnt.xtplayer.ui.LocalAppActions
import com.xuanthanhtnt.xtplayer.ui.MessageDialog
import com.xuanthanhtnt.xtplayer.ui.PosterCard
import com.xuanthanhtnt.xtplayer.ui.Section
import com.xuanthanhtnt.xtplayer.ui.ShelfRow
import com.xuanthanhtnt.xtplayer.ui.TagChip
import com.xuanthanhtnt.xtplayer.ui.focusClickable
import com.xuanthanhtnt.xtplayer.ui.glassPanel
import com.xuanthanhtnt.xtplayer.ui.library.LibraryFilter
import com.xuanthanhtnt.xtplayer.ui.theme.HeroTitleStyle
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.LocalIsTv
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Infuse-style home: a rotating featured hero, then Continue Watching / Recently Added / Movies / TV shelves. */
@Composable
fun HomeScreen() {
    val actions = LocalAppActions.current
    val dao = AppGraph.dao
    val items by remember { dao.itemsFlow() }.collectAsState(initial = emptyList())
    val entries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    val files by remember { dao.filesFlow() }.collectAsState(initial = emptyList())
    val scanning by AppGraph.scanner.isScanning.collectAsState()
    val status by AppGraph.scanner.status.collectAsState()
    val scanError by AppGraph.scanner.lastError.collectAsState()
    val dimens = LocalDimens.current
    val isTv = LocalIsTv.current

    val index = remember(files, entries) { LibraryIndex(files, entries) }
    val continueWatching = remember(entries) { entries.filter { it.resumePosition != null }.take(16) }
    val movies = remember(items) { items.filter { it.kind == LibraryKind.MOVIE } }
    val shows = remember(items) { items.filter { it.kind == LibraryKind.SHOW } }
    val featured = remember(items, continueWatching, index) { featuredItems(items, continueWatching, index) }
    var featuredIndex by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuEntry by remember { mutableStateOf<MediaEntry?>(null) }

    LaunchedEffect(featured.size) {
        // Rotate the hero every 9 s, like the featured banner of streaming apps.
        if (featured.size > 1) while (true) {
            delay(9000)
            featuredIndex++
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item(key = "hero") {
            if (featured.isEmpty()) {
                WelcomeHero()
            } else {
                AnimatedContent(featured[featuredIndex % featured.size], transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hero") { item ->
                    FeaturedHero(
                        item = item,
                        index = index,
                        page = featuredIndex % featured.size,
                        pageCount = featured.size,
                        onPlay = { actions.scope.launch { LibraryActions.start(item, index, actions) } },
                        onDetail = { actions.openDetail(item.key) },
                    )
                }
            }
        }
        if (!isTv) {
            item(key = "search") {
                Row(
                    Modifier
                        .padding(horizontal = dimens.pagePadding)
                        .fillMaxWidth()
                        .focusClickable(RoundedCornerShape(14.dp), focusScale = 1.02f) { actions.openSection(Section.SEARCH.route) }
                        .glassPanel(14.dp)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = XtColors.secondaryText)
                    Spacer(Modifier.size(10.dp))
                    Text("Tìm trong thư viện", color = XtColors.secondaryText)
                }
            }
        }
        if (scanning || scanError != null) {
            item(key = "scan") {
                Row(
                    Modifier
                        .padding(horizontal = dimens.pagePadding)
                        .fillMaxWidth()
                        .glassPanel()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                    }
                    Text(scanError ?: status, color = if (scanError != null) XtColors.danger else XtColors.secondaryText, maxLines = 2)
                }
            }
        }
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                ShelfRow("Tiếp tục xem") {
                    items(continueWatching, key = { it.key }) { entry ->
                        LandscapeCard(
                            title = entry.title,
                            subtitle = entry.subtitle ?: LibraryActions.remaining(entry),
                            imagePath = entry.artworkPath,
                            progress = entry.progress,
                            placeholderIcon = if (entry.isLocalFile) Icons.Filled.Movie else Icons.Filled.Lan,
                            onLongClick = { menuEntry = entry },
                        ) {
                            actions.scope.launch { error = LibraryActions.resume(entry, actions) }
                        }
                    }
                }
            }
        }
        if (items.isNotEmpty()) {
            item(key = "recent") {
                ShelfRow("Mới thêm", onSeeAll = { actions.openGrid(LibraryFilter.RECENT) }) {
                    items(items.take(20), key = { it.key }) { posterItem(it, index) }
                }
            }
        }
        if (movies.isNotEmpty()) {
            item(key = "movies") {
                ShelfRow("Phim", onSeeAll = { actions.openGrid(LibraryFilter.MOVIES) }) {
                    items(movies.take(24), key = { it.key }) { posterItem(it, index) }
                }
            }
        }
        if (shows.isNotEmpty()) {
            item(key = "shows") {
                ShelfRow("TV Shows", onSeeAll = { actions.openGrid(LibraryFilter.SHOWS) }) {
                    items(shows.take(24), key = { it.key }) { posterItem(it, index) }
                }
            }
        }
    }

    MessageDialog("Không mở được", error) { error = null }
    menuEntry?.let { entry ->
        ActionsDialog(
            entry.title,
            listOf(
                "Đánh dấu đã xem" to { actions.scope.launch { MediaLibrary.setWatched(true, entry.key, entry.title) }; Unit },
                "Xoá khỏi danh sách" to { actions.scope.launch { MediaLibrary.remove(entry.key) }; Unit },
            ),
        ) { menuEntry = null }
    }
}

@Composable
private fun posterItem(item: LibraryItem, index: LibraryIndex) {
    val actions = LocalAppActions.current
    val state = index.progress(item)
    PosterCard(item, state.progress, state.watched) { actions.openDetail(item.key) }
}

/** In-progress titles first, then the newest additions with artwork. */
private fun featuredItems(items: List<LibraryItem>, continueWatching: List<MediaEntry>, index: LibraryIndex): List<LibraryItem> {
    val byKey = items.associateBy { it.key }
    val picked = mutableListOf<LibraryItem>()
    val seen = mutableSetOf<String>()
    for (entry in continueWatching) {
        val item = index.itemKey(entry.key)?.let { byKey[it] } ?: continue
        if (seen.add(item.key)) picked += item
    }
    for (item in items) if (item.backdropPath != null && seen.add(item.key)) picked += item
    if (picked.isEmpty()) items.firstOrNull()?.let { picked += it }
    return picked.take(6)
}

/** Full-bleed backdrop with title, facts, synopsis and Play — the top of Infuse's home screen. */
@Composable
private fun FeaturedHero(item: LibraryItem, index: LibraryIndex, page: Int, pageCount: Int, onPlay: () -> Unit, onDetail: () -> Unit) {
    val dimens = LocalDimens.current
    val files = index.files(item)
    val resume = files.mapNotNull { index.entry(it) }.filter { it.resumePosition != null }.maxByOrNull { it.lastPlayedAt }
    Box(
        Modifier
            .fillMaxWidth()
            .height(dimens.heroHeight)
    ) {
        ArtworkImage(item.backdropPath ?: item.posterPath, Modifier.fillMaxSize(), size = "w1280",
            placeholderIcon = if (item.kind == LibraryKind.SHOW) Icons.Filled.Tv else Icons.Filled.Movie)
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to XtColors.background))
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(0f to XtColors.background.copy(alpha = 0.85f), 0.6f to Color.Transparent))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = dimens.pagePadding, vertical = 20.dp)
                .widthIn(max = 640.dp)
        ) {
            Text(item.title, style = HeroTitleStyle(dimens.heroTitle), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                metaParts(item, index).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = XtColors.secondaryText) }
                index.badges(item).take(3).forEach { TagChip(it) }
            }
            item.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroButton(
                    if (resume != null) "Tiếp tục · ${TimeFormat.clock(resume.position)}" else "Phát",
                    Icons.Filled.PlayArrow,
                    primary = true,
                    onClick = onPlay,
                )
                HeroButton("Chi tiết", Icons.Filled.Info, primary = false, onClick = onDetail)
                if (pageCount > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
                        repeat(pageCount) { i ->
                            Box(
                                Modifier
                                    .size(if (i == page) 8.dp else 6.dp)
                                    .background(if (i == page) Color.White else Color.White.copy(alpha = 0.35f), CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun metaParts(item: LibraryItem, index: LibraryIndex): List<String> = buildList {
    item.year?.let { add("$it") }
    if (item.kind == LibraryKind.SHOW) {
        val files = index.files(item)
        val seasons = files.mapNotNull { it.season }.toSet().size.coerceAtLeast(1)
        add("$seasons mùa · ${files.size} tập")
    } else {
        item.runtime?.takeIf { it > 0 }?.let { add(TimeFormat.runtime(it)) }
    }
    item.rating?.takeIf { it > 0 }?.let { add("★ " + String.format(Locale.US, "%.1f", it)) }
}

@Composable
fun HeroButton(text: String, icon: ImageVector, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.05f, onClick = onClick)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, color = if (primary) Color.Black else Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

/** First launch: what the app does and where to start. */
@Composable
private fun WelcomeHero() {
    val actions = LocalAppActions.current
    val dimens = LocalDimens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.pagePadding, vertical = 40.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Image(painterResource(R.drawable.brand_mark), null, Modifier.size(120.dp))
        Spacer(Modifier.height(12.dp))
        Text("XT-Player", style = HeroTitleStyle(dimens.heroTitle))
        Spacer(Modifier.height(8.dp))
        Text(
            "Thêm NAS / máy chủ SMB hoặc WebDAV, chọn thư mục phim và \"Thêm vào thư viện\".\nPoster, nội dung và diễn viên được lấy tự động từ TMDb.",
            style = MaterialTheme.typography.bodyLarge,
            color = XtColors.secondaryText,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroButton("Thêm nguồn", Icons.Filled.AddCircle, primary = true) { actions.openSection(Section.SOURCES.route) }
            HeroButton("Mở tệp", Icons.Filled.Folder, primary = false, onClick = actions.pickFile)
            HeroButton("Mở URL", Icons.Filled.Link, primary = false, onClick = actions.openUrl)
        }
        Spacer(Modifier.height(16.dp))
        Text("Phiên bản ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = XtColors.secondaryText)
    }
}
