package com.xuanthanhtnt.xtplayer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.TimeFormat
import com.xuanthanhtnt.xtplayer.data.LibraryFile
import com.xuanthanhtnt.xtplayer.data.LibraryIndex
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.data.LibraryOrdering
import com.xuanthanhtnt.xtplayer.data.MediaEntry
import com.xuanthanhtnt.xtplayer.player.PlaybackFactory
import com.xuanthanhtnt.xtplayer.player.PlaybackSession

/** Starting playback from the library (shared by Home, grids and the detail page). */
object LibraryActions {
    /** Plays an item: a movie's first version, or a show from the episode to continue with. */
    suspend fun start(item: LibraryItem, index: LibraryIndex, actions: AppActions) {
        val files = index.files(item).sortedWith(LibraryOrdering.episodes)
        if (files.isEmpty()) return
        val queue = PlaybackFactory.library(if (item.kind == LibraryKind.SHOW) files else listOf(files[0]), item)
        if (queue.isEmpty()) return
        val target = index.nextUp(files)
        actions.play(PlaybackSession(queue, queue.indexOfFirst { it.key == target?.key }.coerceAtLeast(0)))
    }

    /** One file of an item; an episode plays with the rest of the show queued behind it. */
    suspend fun start(item: LibraryItem, file: LibraryFile, files: List<LibraryFile>, fromBeginning: Boolean, actions: AppActions) {
        val sorted = files.sortedWith(LibraryOrdering.episodes)
        val queue = PlaybackFactory.library(if (item.kind == LibraryKind.SHOW) sorted else listOf(file), item).toMutableList()
        val index = queue.indexOfFirst { it.key == file.key }
        if (index < 0) return
        if (fromBeginning) queue[index] = queue[index].copy(startAt = null)
        actions.play(PlaybackSession(queue, index))
    }

    /** Re-opens a Continue Watching entry. Returns an error message when the file or server is gone. */
    suspend fun resume(entry: MediaEntry, actions: AppActions): String? {
        val dao = AppGraph.dao
        val file = dao.file(entry.key)
        val item = file?.let { dao.item(it.itemKey) }
        if (file != null && item != null) {
            val siblings = dao.filesOfItem(item.key).sortedWith(LibraryOrdering.episodes)
            val queue = PlaybackFactory.library(if (item.kind == LibraryKind.SHOW) siblings else listOf(file), item)
            val start = queue.indexOfFirst { it.key == entry.key }
            if (start >= 0) {
                actions.play(PlaybackSession(queue, start))
                return null
            }
        }
        val playback = PlaybackFactory.resume(entry) ?: return "Không tìm thấy tệp hoặc máy chủ. Hãy mở lại từ Nguồn."
        actions.play(PlaybackSession(listOf(playback)))
        return null
    }

    fun remaining(entry: MediaEntry): String? =
        if (entry.duration > 0) "Còn ${TimeFormat.clock(maxOf(0.0, entry.duration - entry.position))}" else null
}

@Composable
fun MessageDialog(title: String, message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** A small menu of actions (long press on a card; the context menu of the iOS app). */
@Composable
fun ActionsDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2) },
        text = {
            androidx.compose.foundation.layout.Column {
                actions.forEach { (label, action) ->
                    TextButton(onClick = {
                        onDismiss()
                        action()
                    }) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}
