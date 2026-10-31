package com.xuanthanhtnt.xtplayer.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.ByteFormat
import com.xuanthanhtnt.xtplayer.core.TimeFormat
import com.xuanthanhtnt.xtplayer.data.LibraryFile
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.LibraryOrdering
import com.xuanthanhtnt.xtplayer.data.MediaEntry
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.ui.ActionsDialog
import com.xuanthanhtnt.xtplayer.ui.ArtworkImage
import com.xuanthanhtnt.xtplayer.ui.EmptyText
import com.xuanthanhtnt.xtplayer.ui.LibraryActions
import com.xuanthanhtnt.xtplayer.ui.LocalAppActions
import com.xuanthanhtnt.xtplayer.ui.ProgressLine
import com.xuanthanhtnt.xtplayer.ui.TagChip
import com.xuanthanhtnt.xtplayer.ui.focusClickable
import com.xuanthanhtnt.xtplayer.ui.glassPanel
import com.xuanthanhtnt.xtplayer.ui.home.HeroButton
import com.xuanthanhtnt.xtplayer.ui.library.SortChip
import com.xuanthanhtnt.xtplayer.ui.theme.HeroTitleStyle
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.LocalIsTv
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DetailScreen(itemKey: String) {
    val dao = AppGraph.dao
    val item by remember(itemKey) { dao.itemFlow(itemKey) }.collectAsState(initial = null)
    val itemFiles by remember(itemKey) { dao.filesOfItemFlow(itemKey) }.collectAsState(initial = emptyList())
    val allEntries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    val current = item
    if (current == null) {
        EmptyText("Không tìm thấy mục này trong thư viện.")
        return
    }
    // Route.album of the Apple app: an album opens its track list.
    if (current.kind == LibraryKind.ALBUM) {
        com.xuanthanhtnt.xtplayer.ui.library.AlbumDetailScreen(itemKey)
        return
    }
    val files = remember(itemFiles) { itemFiles.sortedWith(LibraryOrdering.episodes) }
    val entryByKey = remember(itemFiles, allEntries) {
        val keys = itemFiles.map { it.key }.toSet()
        allEntries.filter { it.key in keys }.associateBy { it.key }
    }
    val seasons = remember(files) { files.mapNotNull { it.season }.toSortedSet().toList() }
    val nextUp = remember(files, entryByKey) {
        files.filter { entryByKey[it.key]?.resumePosition != null }.maxByOrNull { entryByKey[it.key]?.lastPlayedAt ?: 0 }
            ?: files.firstOrNull { entryByKey[it.key]?.isWatched != true } ?: files.firstOrNull()
    }
    var season by remember(itemKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(nextUp?.season, seasons) {
        if (season == null) season = nextUp?.season ?: seasons.firstOrNull()
    }
    var menuFile by remember { mutableStateOf<LibraryFile?>(null) }
    val actions = LocalAppActions.current
    val dimens = LocalDimens.current

    Box(Modifier.fillMaxSize()) {
        // Infuse-style page: the backdrop, dimmed, tints the whole detail page.
        ArtworkImage(current.backdropPath ?: current.posterPath, Modifier.fillMaxSize(), size = "w780")
        Box(Modifier.fillMaxSize().background(XtColors.background.copy(alpha = 0.86f)))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "hero") {
                val target = if (current.kind == LibraryKind.SHOW) nextUp else files.firstOrNull()
                Hero(current, files, seasons, target, target?.let { entryByKey[it.key] })
            }
            current.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                item(key = "overview") {
                    Text(
                        overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier
                            .padding(horizontal = dimens.pagePadding)
                            .widthIn(max = 900.dp),
                    )
                }
            }
            if (current.kind == LibraryKind.SHOW) {
                if (seasons.size > 1) {
                    item(key = "seasons") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = dimens.pagePadding, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(seasons) { number ->
                                SortChip(if (number == 0) "Đặc biệt" else "Mùa $number", season == number) { season = number }
                            }
                        }
                    }
                }
                val shown = files.filter { seasons.size <= 1 || it.season == season }
                items(shown, key = { it.key }) { file ->
                    EpisodeRow(
                        file = file,
                        entry = entryByKey[file.key],
                        fallbackImage = current.backdropPath,
                        onLongClick = { menuFile = file },
                    ) {
                        actions.scope.launch { LibraryActions.start(current, file, files, fromBeginning = false, actions = actions) }
                    }
                }
            } else if (files.size > 1) {
                item(key = "versions-title") {
                    Text("Phiên bản", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = dimens.pagePadding))
                }
                items(files, key = { it.key }) { file ->
                    Row(
                        Modifier
                            .padding(horizontal = dimens.pagePadding)
                            .fillMaxWidth()
                            .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.02f, onLongClick = { menuFile = file }) {
                                actions.scope.launch { LibraryActions.start(current, file, files, fromBeginning = false, actions = actions) }
                            }
                            .glassPanel(12.dp)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayCircle, null, tint = XtColors.leaf)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (listOf(ByteFormat.file(file.size)) + file.badges).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = XtColors.secondaryText,
                            )
                        }
                    }
                }
            }
            if (current.castMembers.isNotEmpty()) {
                item(key = "cast") { CastRow(current) }
            }
        }
    }

    menuFile?.let { file ->
        val watched = entryByKey[file.key]?.isWatched == true
        ActionsDialog(
            file.episodeCode?.let { "$it · ${file.episodeTitle.orEmpty()}" } ?: file.fileName,
            listOf(
                "Phát từ đầu" to {
                    actions.scope.launch { LibraryActions.start(current, file, files, fromBeginning = true, actions = actions) }
                    Unit
                },
                (if (watched) "Đánh dấu chưa xem" else "Đánh dấu đã xem") to {
                    actions.scope.launch { MediaLibrary.setWatched(!watched, file.key, current.title) }
                    Unit
                },
            ),
        ) { menuFile = null }
    }
}

/**
 * Infuse's detail hero: a tall backdrop fading into the page, the title, a white Resume / Play button, square
 * action buttons with the facts beside them, then the progress line.
 */
@Composable
private fun Hero(item: LibraryItem, files: List<LibraryFile>, seasons: List<Int>, target: LibraryFile?, entry: MediaEntry?) {
    val actions = LocalAppActions.current
    val dimens = LocalDimens.current
    val isTv = LocalIsTv.current
    val resume = entry?.resumePosition
    var refreshing by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (isTv) 380.dp else dimens.heroHeight)
    ) {
        ArtworkImage(
            item.backdropPath ?: item.posterPath, Modifier.fillMaxSize(), size = "w1280",
            placeholderIcon = if (item.kind == LibraryKind.SHOW) Icons.Filled.Tv else Icons.Filled.Movie,
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Transparent, 1f to XtColors.background.copy(alpha = 0.95f)))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = dimens.pagePadding, vertical = 16.dp)
        ) {
            Text(item.title, style = HeroTitleStyle(dimens.heroTitle), maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.tagline?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = XtColors.secondaryText, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(metaLine(item, files, seasons, entry), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                files.firstOrNull()?.badges?.take(3)?.forEach { TagChip(it) }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (target != null) {
                    val code = if (item.kind == LibraryKind.SHOW) target.episodeCode?.let { " $it" }.orEmpty() else ""
                    HeroButton(
                        if (resume != null) "Tiếp tục$code · ${TimeFormat.fullClock(resume)}" else "Phát$code",
                        Icons.Filled.PlayArrow,
                        primary = true,
                    ) {
                        actions.scope.launch { LibraryActions.start(item, target, files, fromBeginning = false, actions = actions) }
                    }
                    if (resume != null) {
                        HeroButton("Xem từ đầu", Icons.Filled.Replay, primary = false) {
                            actions.scope.launch { LibraryActions.start(item, target, files, fromBeginning = true, actions = actions) }
                        }
                    }
                    if (item.kind == LibraryKind.MOVIE) {
                        val watched = entry?.isWatched == true
                        HeroButton(if (watched) "Đã xem" else "Chưa xem", if (watched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, primary = false) {
                            actions.scope.launch { MediaLibrary.setWatched(!watched, target.key, item.title) }
                        }
                    }
                }
                if (item.kind == LibraryKind.MOVIE || item.kind == LibraryKind.SHOW) HeroButton(if (refreshing) "Đang tải…" else "Làm mới", Icons.Filled.Refresh, primary = false) {
                    if (!refreshing) {
                        refreshing = true
                        actions.scope.launch {
                            AppGraph.scanner.refresh(item)
                            refreshing = false
                        }
                    }
                }
            }
            if (entry != null && resume != null && entry.duration > 0) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 520.dp)) {
                    ProgressLine(entry.position / entry.duration, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Còn ${TimeFormat.clock(entry.duration - entry.position)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = XtColors.secondaryText,
                    )
                }
            }
        }
    }
}

/** "2019   2 giờ 10 phút   ★ 7.8   Hành động, Phiêu lưu" — Infuse's facts next to the action buttons. */
private fun metaLine(item: LibraryItem, files: List<LibraryFile>, seasons: List<Int>, entry: MediaEntry?): String {
    val parts = mutableListOf<String>()
    item.year?.let { parts += "$it" }
    if (item.kind == LibraryKind.SHOW) {
        parts += "${seasons.size.coerceAtLeast(1)} mùa · ${files.size} tập"
    } else {
        val runtime = item.runtime ?: entry?.duration?.takeIf { it > 0 }?.let { (it / 60).toInt() }
        if (runtime != null && runtime > 0) parts += TimeFormat.runtime(runtime)
    }
    item.rating?.takeIf { it > 0 }?.let { parts += "★ " + String.format(Locale.US, "%.1f", it) }
    if (item.genres.isNotEmpty() && item.kind == LibraryKind.MOVIE) parts += item.genres.take(2).joinToString(", ")
    return parts.joinToString("   ")
}

@Composable
private fun EpisodeRow(file: LibraryFile, entry: MediaEntry?, fallbackImage: String?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val dimens = LocalDimens.current
    Row(
        Modifier
            .padding(horizontal = dimens.pagePadding)
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(14.dp), focusScale = 1.02f, onLongClick = onLongClick, onClick = onClick)
            .glassPanel(14.dp)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (LocalIsTv.current) 220.dp else 160.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            ArtworkImage(file.stillPath ?: fallbackImage, Modifier.fillMaxSize(), size = "w300", placeholderIcon = Icons.Filled.Tv)
            entry?.progress?.takeIf { entry.resumePosition != null }?.let {
                ProgressLine(it, Modifier.align(Alignment.BottomCenter).padding(6.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(file.episodeCode, file.episodeTitle).joinToString(" · ").ifEmpty { file.fileName },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry?.isWatched == true) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.CheckCircle, "Đã xem", tint = XtColors.leaf, modifier = Modifier.size(18.dp))
                }
            }
            file.episodeOverview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(file.airDate, ByteFormat.file(file.size), file.badges.takeIf { it.isNotEmpty() }?.joinToString(" ")).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = XtColors.secondaryText,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CastRow(item: LibraryItem) {
    val dimens = LocalDimens.current
    Column {
        Text("Diễn viên", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = dimens.pagePadding))
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(item.castMembers, key = { it.name + it.character }) { person ->
                Column(
                    Modifier
                        .width(dimens.castSize + 24.dp)
                        .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.05f) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArtworkImage(
                        person.profilePath, Modifier
                            .size(dimens.castSize)
                            .clip(CircleShape), size = "w185", placeholderIcon = Icons.Filled.Person,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(person.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(person.character, style = MaterialTheme.typography.labelSmall, color = XtColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
