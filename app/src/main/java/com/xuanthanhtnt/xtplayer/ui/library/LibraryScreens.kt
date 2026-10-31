package com.xuanthanhtnt.xtplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.NaturalOrder
import com.xuanthanhtnt.xtplayer.data.LibraryIndex
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.ui.EmptyText
import com.xuanthanhtnt.xtplayer.ui.LocalAppActions
import com.xuanthanhtnt.xtplayer.ui.PageTitle
import com.xuanthanhtnt.xtplayer.ui.PosterCard
import com.xuanthanhtnt.xtplayer.ui.ShelfRow
import com.xuanthanhtnt.xtplayer.ui.focusClickable
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import java.text.Normalizer

/** What a library grid shows (sidebar entries on TV / tablets, "Xem tất cả" from the home shelves). */
enum class LibraryFilter(val title: String, val emptyText: String) {
    MOVIES("Phim", "Thư viện trống — thêm thư mục từ mục Nguồn."),
    SHOWS("TV Shows", "Thư viện trống — thêm thư mục từ mục Nguồn."),
    RECENT("Mới thêm", "Thư viện trống — thêm thư mục từ mục Nguồn."),
    UNWATCHED("Chưa xem", "Bạn đã xem hết mọi thứ trong thư viện."),
    TOP_RATED("Điểm cao", "Chưa có mục nào có điểm TMDb."),
}

private enum class Sort(val label: String) { RECENT("Mới thêm"), TITLE("Tên"), YEAR("Năm"), RATING("Điểm") }

@Composable
fun LibraryGridScreen(filter: LibraryFilter) {
    val dao = AppGraph.dao
    val all by remember { dao.itemsFlow() }.collectAsState(initial = emptyList())
    val files by remember { dao.filesFlow() }.collectAsState(initial = emptyList())
    val entries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    var sort by rememberSaveable { mutableStateOf(if (filter == LibraryFilter.TOP_RATED) Sort.RATING else Sort.RECENT) }
    val index = remember(files, entries) { LibraryIndex(files, entries) }
    val shown = remember(all, index, sort, filter) { filtered(all, index, filter, sort) }
    val dimens = LocalDimens.current
    val actions = LocalAppActions.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(dimens.posterWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                PageTitle(filter.title, inset = false)
                if (filter != LibraryFilter.RECENT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Sort.entries.forEach { option -> SortChip(option.label, option == sort) { sort = option } }
                    }
                }
                if (shown.isEmpty()) EmptyText(filter.emptyText, inset = false)
            }
        }
        items(shown, key = { it.key }) { item ->
            val state = index.progress(item)
            PosterCard(item, state.progress, state.watched, Modifier.fillMaxWidth()) { actions.openDetail(item.key) }
        }
    }
}

private fun filtered(all: List<LibraryItem>, index: LibraryIndex, filter: LibraryFilter, sort: Sort): List<LibraryItem> {
    val list = when (filter) {
        LibraryFilter.MOVIES -> all.filter { it.kind == LibraryKind.MOVIE }
        LibraryFilter.SHOWS -> all.filter { it.kind == LibraryKind.SHOW }
        LibraryFilter.RECENT -> return all.filter { it.kind == LibraryKind.MOVIE || it.kind == LibraryKind.SHOW }
            .sortedByDescending { it.addedAt }.take(60)
        LibraryFilter.UNWATCHED -> all.filter { it.kind != LibraryKind.ALBUM && index.isUnplayed(it) }
        LibraryFilter.TOP_RATED -> all.filter { (it.rating ?: 0.0) > 0 }
    }
    return when (sort) {
        Sort.RECENT -> list.sortedByDescending { it.addedAt }
        Sort.TITLE -> list.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
        Sort.YEAR -> list.sortedByDescending { it.year ?: 0 }
        Sort.RATING -> list.sortedByDescending { it.rating ?: 0.0 }
    }
}

@Composable
fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .focusClickable(CircleShape, focusScale = 1.05f, onClick = onClick)
            .background(if (selected) XtColors.brandGradient else androidx.compose.ui.graphics.SolidColor(XtColors.glass), CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One shelf per genre (Infuse's "Movie Genres"). */
@Composable
fun GenresScreen() {
    val dao = AppGraph.dao
    val all by remember { dao.itemsFlow() }.collectAsState(initial = emptyList())
    val files by remember { dao.filesFlow() }.collectAsState(initial = emptyList())
    val entries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    val index = remember(files, entries) { LibraryIndex(files, entries) }
    val actions = LocalAppActions.current
    val genres = remember(all) {
        all.flatMap { item -> item.genres.map { it to item } }
            .groupBy({ it.first }, { it.second })
            .toList()
            .sortedByDescending { it.second.size }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageTitle("Thể loại") }
        if (genres.isEmpty()) item { EmptyText("Chưa có thể loại — thêm khoá TMDb trong Cài đặt rồi quét lại thư viện.") }
        items(genres, key = { it.first }) { (genre, genreItems) ->
            ShelfRow(genre) {
                items(genreItems.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }, key = { it.key }) { item ->
                    val state = index.progress(item)
                    PosterCard(item, state.progress, state.watched) { actions.openDetail(item.key) }
                }
            }
        }
    }
}

/** Searches titles, original titles, cast and genres — accents optional ("chuyen" finds "Chuyện"). */
@Composable
fun SearchScreen() {
    val dao = AppGraph.dao
    val all by remember { dao.itemsFlow() }.collectAsState(initial = emptyList())
    val files by remember { dao.filesFlow() }.collectAsState(initial = emptyList())
    val entries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    val index = remember(files, entries) { LibraryIndex(files, entries) }
    var query by rememberSaveable { mutableStateOf("") }
    val actions = LocalAppActions.current
    val dimens = LocalDimens.current
    val results = remember(all, query) {
        val needle = fold(query.trim())
        if (needle.isEmpty()) emptyList() else all.filter { item ->
            fold(item.title).contains(needle) || fold(item.originalTitle.orEmpty()).contains(needle) ||
                item.genres.any { fold(it).contains(needle) } || item.cast.any { fold(it.substringBefore('|')).contains(needle) }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(dimens.posterWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                PageTitle("Tìm kiếm", inset = false)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Tên phim, diễn viên, thể loại…") },
                )
                if (query.isNotBlank() && results.isEmpty()) EmptyText("Không tìm thấy \"$query\" trong thư viện.", inset = false)
            }
        }
        items(results, key = { it.key }) { item ->
            val state = index.progress(item)
            PosterCard(item, state.progress, state.watched, Modifier.fillMaxWidth()) { actions.openDetail(item.key) }
        }
    }
}

private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')

/** MusicViews.swift `MusicVideosView`: every music video, newest first or by artist / show. */
@Composable
fun MusicVideosScreen(embedded: Boolean = false) {
    val dao = AppGraph.dao
    val all by remember { dao.itemsFlow() }.collectAsState(initial = emptyList())
    val files by remember { dao.filesFlow() }.collectAsState(initial = emptyList())
    val entries by remember { dao.entriesFlow() }.collectAsState(initial = emptyList())
    val index = remember(files, entries) { LibraryIndex(files, entries) }
    var byArtist by rememberSaveable { mutableStateOf(false) }
    val dimens = LocalDimens.current
    val videos = remember(all, byArtist) {
        val list = all.filter { it.kind == LibraryKind.MUSIC_VIDEO }
        if (!byArtist) list else list.sortedWith(compareBy<LibraryItem>({ it.artist ?: "~" }, { it.title }))
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(dimens.landscapeWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (!embedded) PageTitle("Video ca nhạc", inset = false)
                if (videos.isEmpty()) {
                    MusicEmptyHint(com.xuanthanhtnt.xtplayer.data.LibraryFolderKind.MUSIC_VIDEOS)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        SortChip("Mới thêm", !byArtist) { byArtist = false }
                        SortChip("Nghệ sĩ / chương trình", byArtist) { byArtist = true }
                    }
                }
            }
        }
        items(videos, key = { it.key }) { MusicVideoCard(it, index, Modifier.fillMaxWidth()) }
    }
}

/** MusicViews.swift `MusicVideoLink`: a 16:9 card (artist / show under the title) that opens the detail page. */
@Composable
fun MusicVideoCard(item: LibraryItem, index: LibraryIndex, modifier: Modifier = Modifier) {
    val actions = LocalAppActions.current
    val state = index.progress(item)
    androidx.compose.foundation.layout.Box(modifier) {
        com.xuanthanhtnt.xtplayer.ui.LandscapeCard(
            title = item.title,
            subtitle = item.artist ?: item.year?.toString(),
            imagePath = item.backdropPath,
            progress = state.progress,
            modifier = Modifier.fillMaxWidth(),
            placeholderIcon = androidx.compose.material.icons.Icons.Filled.MusicVideo,
        ) { actions.openDetail(item.key) }
        if (state.watched) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.CheckCircle, "Đã xem", tint = XtColors.leaf,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
    }
}
