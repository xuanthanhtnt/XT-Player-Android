package com.xuanthanhtnt.xtplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.ByteFormat
import com.xuanthanhtnt.xtplayer.core.NaturalOrder
import com.xuanthanhtnt.xtplayer.data.LibraryFile
import com.xuanthanhtnt.xtplayer.data.LibraryFolderKind
import com.xuanthanhtnt.xtplayer.data.LibraryIndex
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.LibraryOrdering
import com.xuanthanhtnt.xtplayer.player.PlaybackFactory
import com.xuanthanhtnt.xtplayer.player.PlaybackSession
import com.xuanthanhtnt.xtplayer.ui.AppActions
import com.xuanthanhtnt.xtplayer.ui.ArtworkImage
import com.xuanthanhtnt.xtplayer.ui.EmptyText
import com.xuanthanhtnt.xtplayer.ui.LocalAppActions
import com.xuanthanhtnt.xtplayer.ui.PageTitle
import com.xuanthanhtnt.xtplayer.ui.focusClickable
import com.xuanthanhtnt.xtplayer.ui.home.HeroButton
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.LocalIsTv
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.launch

// Music in the library (MusicViews.swift, v0.3.0): albums (folders of tracks from "Nhạc" library folders) and
// music videos (files of "Video ca nhạc" folders), each with its own grid, kept apart from films and TV shows.

/** AlbumCard.width: 170 pt, 300 pt on Apple TV (smaller dp here, as the TV layout is). */
@Composable
fun albumCardWidth(): Dp = if (LocalIsTv.current) 150.dp else 160.dp

/** Square album cover with title and artist. */
@Composable
fun AlbumCard(item: LibraryItem, played: Boolean, modifier: Modifier = Modifier.width(albumCardWidth()), onClick: () -> Unit) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .focusClickable(RoundedCornerShape(10.dp), onClick = onClick)
        ) {
            ArtworkImage(item.posterPath, Modifier.fillMaxSize(), size = "w342", placeholderIcon = Icons.Filled.MusicNote)
            if (played) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .background(Color.White, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CheckCircle, "Đã nghe", tint = XtColors.leaf, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            item.artist ?: item.year?.toString() ?: "Album",
            style = MaterialTheme.typography.bodySmall,
            color = XtColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** AlbumLink: the card that opens the album page. */
@Composable
fun AlbumLink(item: LibraryItem, index: LibraryIndex, modifier: Modifier = Modifier.width(albumCardWidth())) {
    val actions = LocalAppActions.current
    AlbumCard(item, played = index.progress(item).watched, modifier = modifier) { actions.openDetail(item.key) }
}

private enum class AlbumSort(val label: String) { RECENT("Mới thêm"), ARTIST("Nghệ sĩ"), TITLE("Tên") }

/** AlbumsView: all albums, newest first or by artist / title. */
@Composable
fun AlbumsScreen(embedded: Boolean = false) {
    val dao = AppGraph.dao
    val all by remember { dao.itemsFlow() }.collectAsState(initial = emptyList())
    val files by remember { dao.filesFlow() }.collectAsState(initial = emptyList())
    val entries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    val index = remember(files, entries) { LibraryIndex(files, entries) }
    var sort by rememberSaveable { mutableStateOf(AlbumSort.RECENT) }
    val dimens = LocalDimens.current
    val albums = remember(all, sort) {
        val list = all.filter { it.kind == LibraryKind.ALBUM }
        when (sort) {
            AlbumSort.RECENT -> list
            AlbumSort.TITLE -> list.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
            AlbumSort.ARTIST -> list.sortedWith(compareBy<LibraryItem>({ it.artist ?: "~" }, { it.year ?: 0 }, { it.title }))
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(albumCardWidth()),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (!embedded) PageTitle("Nhạc", inset = false)
                if (albums.isEmpty()) {
                    MusicEmptyHint(LibraryFolderKind.MUSIC)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        AlbumSort.entries.forEach { option -> SortChip(option.label, sort == option) { sort = option } }
                    }
                }
            }
        }
        items(albums, key = { it.key }) { AlbumLink(it, index, Modifier.fillMaxWidth()) }
    }
}

/** MusicLibraryView: the phone's "Nhạc" tab, albums or music videos. */
@Composable
fun MusicLibraryScreen() {
    var showVideos by rememberSaveable { mutableStateOf(false) }
    val dimens = LocalDimens.current
    Column(Modifier.fillMaxSize()) {
        PageTitle("Nhạc")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = dimens.pagePadding).padding(bottom = 12.dp)) {
            SortChip("Album", !showVideos) { showVideos = false }
            SortChip("Video ca nhạc", showVideos) { showVideos = true }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showVideos) MusicVideosScreen(embedded = true) else AlbumsScreen(embedded = true)
        }
    }
}

/** MusicEmptyHint: what to do when a music section is empty. */
@Composable
fun MusicEmptyHint(kind: LibraryFolderKind) {
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(folderKindIcon(kind), null, tint = XtColors.leaf)
            Text(
                if (kind == LibraryFolderKind.MUSIC) "Chưa có album nào" else "Chưa có video ca nhạc nào",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            "Mở thư mục trên NAS ở mục Nguồn, bấm ▾ cạnh “Thêm thư mục này vào thư viện” và chọn “${kind.label}”. " +
                "Thư mục đã thêm: nhấn giữ ở mục Nguồn ▸ Loại thư mục.",
            style = MaterialTheme.typography.bodyMedium,
            color = XtColors.secondaryText,
        )
    }
}

private fun folderKindIcon(kind: LibraryFolderKind): ImageVector = when (kind) {
    LibraryFolderKind.MUSIC -> Icons.Filled.LibraryMusic
    LibraryFolderKind.MUSIC_VIDEOS -> Icons.Filled.MusicVideo
    LibraryFolderKind.FILMS -> Icons.Filled.MusicNote
}

/** AlbumDetailView: cover, artist, track list; plays from any track to the end of the album. */
@Composable
fun AlbumDetailScreen(itemKey: String) {
    val dao = AppGraph.dao
    val album by remember(itemKey) { dao.itemFlow(itemKey) }.collectAsState(initial = null)
    val files by remember(itemKey) { dao.filesOfItemFlow(itemKey) }.collectAsState(initial = emptyList())
    val entries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    val actions = LocalAppActions.current
    val dimens = LocalDimens.current
    val current = album
    if (current == null) {
        EmptyText("Không tìm thấy mục này trong thư viện.")
        return
    }
    val tracks = remember(files) { files.sortedWith(LibraryOrdering.episodes) }
    val entryByKey = remember(entries) { entries.associateBy { it.key } }
    val discs = remember(tracks) { tracks.mapNotNull { it.season }.toSet() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, top = 16.dp, bottom = 32.dp),
    ) {
        item(key = "header") {
            AlbumHeader(current, tracks, actions)
            Spacer(Modifier.height(24.dp))
        }
        itemsIndexed(tracks, key = { _, track -> track.key }) { position, track ->
            if (discs.size > 1 && (position == 0 || track.season != tracks[position - 1].season)) {
                Text(
                    "Đĩa ${track.season ?: 1}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = XtColors.secondaryText,
                    modifier = Modifier.padding(top = if (position == 0) 0.dp else 16.dp, bottom = 6.dp),
                )
            }
            TrackRow(
                number = track.episode ?: (position + 1),
                title = track.episodeTitle ?: track.fileName,
                format = track.badges.firstOrNull(),
                played = entryByKey[track.key]?.isWatched ?: false,
            ) { playAlbum(current, tracks, position, actions) }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun AlbumHeader(album: LibraryItem, tracks: List<LibraryFile>, actions: AppActions) {
    val isTv = LocalIsTv.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cover = if (isTv) 240.dp else 200.dp
        // Side by side as on the Mac and iPad; stacked on a narrow phone.
        val stacked = maxWidth < cover + 320.dp
        val info: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(album.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                album.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = XtColors.leaf) }
                Text(summary(album, tracks), style = MaterialTheme.typography.bodyMedium, color = XtColors.secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    HeroButton("Phát", Icons.Filled.PlayArrow, primary = true) { playAlbum(album, tracks, 0, actions) }
                    HeroButton("Trộn bài", Icons.Filled.Shuffle, primary = false) { playAlbum(album, tracks.shuffled(), 0, actions) }
                }
            }
        }
        val art: @Composable () -> Unit = {
            ArtworkImage(
                album.posterPath,
                Modifier
                    .size(cover)
                    .clip(RoundedCornerShape(12.dp)),
                size = "w500",
                placeholderIcon = Icons.Filled.MusicNote,
            )
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                art()
                info()
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                art()
                info()
            }
        }
    }
}

/** "2019 · 12 bài · FLAC · 412 MB". */
private fun summary(album: LibraryItem, tracks: List<LibraryFile>): String {
    val parts = mutableListOf<String>()
    album.year?.let { parts += it.toString() }
    parts += "${tracks.size} bài"
    val formats = tracks.mapNotNull { it.badges.firstOrNull() }.toSortedSet()
    if (formats.isNotEmpty()) parts += formats.joinToString(" / ")
    parts += ByteFormat.file(tracks.sumOf { it.size })
    return parts.joinToString(" · ")
}

private fun playAlbum(album: LibraryItem, tracks: List<LibraryFile>, from: Int, actions: AppActions) {
    actions.scope.launch {
        val queue = PlaybackFactory.library(tracks, album)
        if (queue.isEmpty()) return@launch
        actions.play(PlaybackSession(queue, minOf(from, queue.size - 1)))
    }
}

@Composable
private fun TrackRow(number: Int, title: String, format: String?, played: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(8.dp), focusScale = 1.02f, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = XtColors.secondaryText,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp),
        )
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (played) Icon(Icons.Filled.Check, "Đã nghe", tint = XtColors.leaf, modifier = Modifier.size(16.dp))
        if (format != null) {
            Text(format, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = XtColors.secondaryText)
        }
    }
}
