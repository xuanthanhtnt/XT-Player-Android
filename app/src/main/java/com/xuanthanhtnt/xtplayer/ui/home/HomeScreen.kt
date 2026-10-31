package com.xuanthanhtnt.xtplayer.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.R
import com.xuanthanhtnt.xtplayer.data.LibraryIndex
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.LibraryOrdering
import com.xuanthanhtnt.xtplayer.data.MediaEntry
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.metadata.FilenameParser
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
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Infuse-style home, as in the Apple app's HomeView: a rotating featured hero, then Continue Watching /
 * Recently Added / Movies / TV shelves.
 */
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

    val index = remember(files, entries) { LibraryIndex(files, entries) }
    // Films and episodes left half-watched (music is resumed from its album, not from here).
    val continueWatching = remember(entries) {
        entries.filter { it.resumePosition != null && it.uri.substringAfterLast('.', "").lowercase() !in FilenameParser.audioExtensions }.take(16)
    }
    val filmsAndShows = remember(items) { items.filter { it.kind == LibraryKind.MOVIE || it.kind == LibraryKind.SHOW } }
    val movies = remember(items) { items.filter { it.kind == LibraryKind.MOVIE } }
    val shows = remember(items) { items.filter { it.kind == LibraryKind.SHOW } }
    val musicVideos = remember(items) { items.filter { it.kind == LibraryKind.MUSIC_VIDEO } }
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
        contentPadding = PaddingValues(bottom = dimens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        item(key = "hero") {
            if (featured.isEmpty()) {
                WelcomeHero()
            } else {
                AnimatedContent(
                    featured[featuredIndex % featured.size],
                    transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) },
                    label = "hero",
                ) { item ->
                    FeaturedHero(
                        item = item,
                        index = index,
                        pageCount = featured.size,
                        page = featuredIndex % featured.size,
                        onPlay = { actions.scope.launch { LibraryActions.start(item, index, actions) } },
                        onDetail = { actions.openDetail(item.key) },
                    )
                }
            }
        }
        if (scanning || scanError != null) {
            item(key = "scan") {
                Row(
                    Modifier
                        .padding(horizontal = dimens.pagePadding)
                        .fillMaxWidth()
                        .glassPanel(dimens.corner)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        scanError ?: status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (scanError != null) XtColors.danger else XtColors.secondaryText,
                        maxLines = 2,
                    )
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
        if (filmsAndShows.isNotEmpty()) {
            item(key = "recent") {
                ShelfRow("Mới thêm") {
                    items(filmsAndShows.take(20), key = { it.key }) { posterItem(it, index) }
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
        if (musicVideos.isNotEmpty()) {
            item(key = "musicVideos") {
                ShelfRow("Video ca nhạc", onSeeAll = { actions.openSection(com.xuanthanhtnt.xtplayer.ui.Section.MUSIC_VIDEOS.route) }) {
                    items(musicVideos.take(20), key = { it.key }) { com.xuanthanhtnt.xtplayer.ui.library.MusicVideoCard(it, index) }
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
    for (item in items) if (item.kind != LibraryKind.ALBUM && item.backdropPath != null && seen.add(item.key)) picked += item
    picked.removeAll { it.kind == LibraryKind.ALBUM }
    if (picked.isEmpty()) items.firstOrNull { it.kind != LibraryKind.ALBUM }?.let { picked += it }
    return picked.take(6)
}

/** Full-bleed backdrop with kind, title, facts, synopsis and Play — the top of Infuse's home screen. */
@Composable
private fun FeaturedHero(item: LibraryItem, index: LibraryIndex, pageCount: Int, page: Int, onPlay: () -> Unit, onDetail: () -> Unit) {
    val dimens = LocalDimens.current
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
                .background(Brush.verticalGradient(listOf(Color.Transparent, XtColors.background.copy(alpha = 0.35f), XtColors.background)))
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(0f to XtColors.background.copy(alpha = 0.85f), 0.5f to Color.Transparent))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (item.kind) {
                    LibraryKind.SHOW -> "TV SHOW"
                    LibraryKind.MUSIC_VIDEO -> "VIDEO CA NHẠC"
                    else -> "PHIM"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp),
                color = XtColors.leaf,
            )
            Text(
                item.title,
                style = TextStyle(
                    fontSize = dimens.heroTitle.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = (dimens.heroTitle * 1.1).sp,
                    shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 16f),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(facts(item), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Color.White.copy(alpha = 0.8f))
                index.badges(item).take(4).forEach { TagChip(it, highlighted = it == "DV" || it.startsWith("HDR")) }
            }
            item.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroButton(playTitle(item, index), Icons.Filled.PlayArrow, primary = true, onClick = onPlay)
                HeroButton("Chi tiết", Icons.Outlined.Info, primary = false, onClick = onDetail)
                Spacer(Modifier.weight(1f))
                if (pageCount > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(pageCount) { i ->
                            Box(
                                Modifier
                                    .size(width = if (i == page) 18.dp else 6.dp, height = 6.dp)
                                    .background(if (i == page) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "2019 · 2h 10m · ★ 7.8 · Hành động" */
private fun facts(item: LibraryItem): String = buildList {
    item.year?.let { add("$it") }
    if (item.kind == LibraryKind.MOVIE) item.runtime?.takeIf { it > 0 }?.let { add("${it / 60}h ${it % 60}m") }
    item.rating?.takeIf { it > 0 }?.let { add("★ " + String.format(Locale.US, "%.1f", it)) }
    item.genres.firstOrNull()?.let { add(it) }
}.joinToString(" · ")

/** "Phát" / "Tiếp tục", with the episode for a show: "Tiếp tục S01E02". */
private fun playTitle(item: LibraryItem, index: LibraryIndex): String {
    val next = index.nextUp(index.files(item).sortedWith(LibraryOrdering.episodes)) ?: return "Phát"
    val code = if (item.kind == LibraryKind.SHOW) next.episodeCode?.let { " $it" }.orEmpty() else ""
    return if (index.entry(next)?.resumePosition != null) "Tiếp tục$code" else "Phát$code"
}

/** Large button of the heroes: white (prominent) or translucent (bordered). */
@Composable
fun HeroButton(text: String, icon: ImageVector, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.05f, onClick = onClick)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, color = if (primary) Color.Black else Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Shown until the library has something in it. */
@Composable
private fun WelcomeHero() {
    val actions = LocalAppActions.current
    val dimens = LocalDimens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = dimens.pagePadding, end = dimens.pagePadding, top = 90.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Image(
            painterResource(R.drawable.brand_mark), null,
            Modifier
                .size(150.dp)
                .shadow(30.dp, CircleShape, clip = false, ambientColor = XtColors.forest, spotColor = XtColors.forest),
        )
        Text(
            "XT-Player",
            style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, brush = XtColors.brandGradient),
        )
        Text(
            "Thêm NAS / máy chủ SMB hoặc WebDAV, chọn thư mục phim và \"Thêm vào thư viện\".\nPoster, nội dung và diễn viên được lấy tự động từ TMDb.",
            style = MaterialTheme.typography.bodyMedium,
            color = XtColors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 620.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier
                    .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.05f) { actions.openSection(Section.SOURCES.route) }
                    .background(XtColors.accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AddCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Thêm nguồn", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            }
            Row(
                Modifier
                    .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.05f, onClick = actions.pickFile)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, null, tint = XtColors.leaf, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Mở tệp", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
