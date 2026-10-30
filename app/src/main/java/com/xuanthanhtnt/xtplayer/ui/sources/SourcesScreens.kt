package com.xuanthanhtnt.xtplayer.ui.sources

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.core.ByteFormat
import com.xuanthanhtnt.xtplayer.core.NaturalOrder
import com.xuanthanhtnt.xtplayer.data.LibraryFolder
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.data.MediaServer
import com.xuanthanhtnt.xtplayer.metadata.FilenameParser
import com.xuanthanhtnt.xtplayer.metadata.ThumbnailStore
import com.xuanthanhtnt.xtplayer.network.DiscoveredServer
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystemPool
import com.xuanthanhtnt.xtplayer.network.RemoteFileSystems
import com.xuanthanhtnt.xtplayer.network.RemoteItem
import com.xuanthanhtnt.xtplayer.network.ServerCredentials
import com.xuanthanhtnt.xtplayer.network.ServerDiscovery
import com.xuanthanhtnt.xtplayer.network.ServerKind
import com.xuanthanhtnt.xtplayer.network.ServerRegistry
import com.xuanthanhtnt.xtplayer.player.FrameGrabber
import com.xuanthanhtnt.xtplayer.player.PlaybackFactory
import com.xuanthanhtnt.xtplayer.player.PlaybackSession
import com.xuanthanhtnt.xtplayer.ui.ActionsDialog
import com.xuanthanhtnt.xtplayer.ui.EmptyText
import com.xuanthanhtnt.xtplayer.ui.LocalAppActions
import com.xuanthanhtnt.xtplayer.ui.PageTitle
import com.xuanthanhtnt.xtplayer.ui.SourceCard
import com.xuanthanhtnt.xtplayer.ui.focusClickable
import com.xuanthanhtnt.xtplayer.ui.glassPanel
import com.xuanthanhtnt.xtplayer.ui.library.SortChip
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** "Nguồn": servers, library folders and quick open actions — Infuse's "Shares" screen. */
@Composable
fun SourcesScreen() {
    val dao = AppGraph.dao
    val servers by remember { dao.serversFlow() }.collectAsState(initial = emptyList())
    val folders by remember { dao.foldersFlow() }.collectAsState(initial = emptyList())
    val scanning by AppGraph.scanner.isScanning.collectAsState()
    val status by AppGraph.scanner.status.collectAsState()
    val actions = LocalAppActions.current
    val dimens = LocalDimens.current
    var menuServer by remember { mutableStateOf<MediaServer?>(null) }
    var menuFolder by remember { mutableStateOf<LibraryFolder?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(340.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageTitle("Nguồn", inset = false) }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Máy chủ") }
        items(servers, key = { it.id }) { server ->
            SourceCard(
                icon = if (server.kind == ServerKind.SMB) Icons.Filled.Dns else Icons.Filled.Language,
                title = server.name,
                subtitle = "${server.kind.label} · ${server.displayHost}",
                onLongClick = { menuServer = server },
            ) { actions.openBrowser(server.id, "", server.name) }
        }
        item {
            SourceCard(Icons.Filled.AddCircle, "Thêm máy chủ", "SMB (Windows / NAS) hoặc WebDAV — tự tìm NAS trong mạng") { actions.addServer() }
        }
        item {
            SourceCard(Icons.Filled.FolderOpen, "Mở tệp trên thiết bị", "Bộ nhớ máy, USB, thẻ nhớ", onClick = actions.pickFile)
        }
        item {
            SourceCard(Icons.Filled.Link, "Mở URL", "Đường dẫn http(s) tới tệp video", onClick = actions.openUrl)
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionLabel("Thư mục thư viện")
                if (scanning || status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
                }
                if (folders.isEmpty()) {
                    Text(
                        "Mở một máy chủ, vào thư mục phim rồi chọn \"Thêm thư mục này vào thư viện\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XtColors.secondaryText,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
        items(folders, key = { it.key }) { folder ->
            val serverName = servers.firstOrNull { it.id == folder.serverID }?.name.orEmpty()
            SourceCard(
                icon = Icons.Filled.Folder,
                title = folder.name,
                subtitle = folderDetail(folder, serverName),
                onLongClick = { menuFolder = folder },
            ) { actions.openBrowser(folder.serverID, folder.path, folder.name) }
        }
    }

    menuServer?.let { server ->
        ActionsDialog(
            server.name,
            listOf(
                "Mở" to { actions.openBrowser(server.id, "", server.name); Unit },
                "Xoá máy chủ" to { actions.scope.launch { MediaLibrary.removeServer(server) }; Unit },
            ),
        ) { menuServer = null }
    }
    menuFolder?.let { folder ->
        ActionsDialog(
            folder.name,
            listOf(
                "Quét lại" to { AppGraph.scanner.scanInBackground(folder) },
                "Bỏ khỏi thư viện" to { actions.scope.launch { MediaLibrary.removeFolder(folder) }; Unit },
            ),
        ) { menuFolder = null }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

private fun folderDetail(folder: LibraryFolder, serverName: String): String {
    val scanned = folder.lastScan?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
    return listOfNotNull(serverName.takeIf { it.isNotEmpty() }, "${folder.fileCount} tệp", scanned?.let { "quét lúc $it" }).joinToString(" · ") +
        "\nGiữ (nhấn lâu) để quét lại hoặc bỏ khỏi thư viện"
}

/** A folder of a server: sub-folders, then videos with a frame of each as its picture. */
@Composable
fun BrowserScreen(serverID: String, path: String, title: String) {
    val dao = AppGraph.dao
    val actions = LocalAppActions.current
    val folders by remember { dao.foldersFlow() }.collectAsState(initial = emptyList())
    val scanning by AppGraph.scanner.isScanning.collectAsState()
    val status by AppGraph.scanner.status.collectAsState()
    val scanError by AppGraph.scanner.lastError.collectAsState()
    val dimens = LocalDimens.current
    var reload by remember { mutableIntStateOf(0) }
    val server by produceState<ServerCredentials?>(null, serverID) {
        value = dao.server(serverID)?.credentials?.also(ServerRegistry::register)
    }
    var listing by remember(serverID, path) { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var loading by remember(serverID, path) { mutableStateOf(true) }
    var error by remember(serverID, path) { mutableStateOf<String?>(null) }

    LaunchedEffect(server, path, reload) {
        val credentials = server ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            listing = RemoteFileSystems.make(credentials).list(path)
        } catch (e: Exception) {
            error = "Không mở được: ${e.message ?: e}"
            RemoteFileSystemPool.reset(serverID)
        }
        loading = false
    }

    val directories = remember(listing) { listing.filter { it.isDirectory }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) } }
    val videos = remember(listing) { listing.filter { it.isVideo }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) } }
    val subtitles = remember(listing) { listing.filter { it.isSubtitle } }
    val libraryFolder = folders.firstOrNull { it.serverID == serverID && it.path == path }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                PageTitle(title.ifEmpty { server?.displayHost ?: "" })
                if (path.isNotEmpty()) {
                    Text(path, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, modifier = Modifier.padding(horizontal = dimens.pagePadding))
                }
            }
        }
        if (path.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = dimens.pagePadding)) {
                    Row(
                        Modifier
                            .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.03f) {
                                if (scanning) return@focusClickable
                                actions.scope.launch {
                                    val folder = libraryFolder ?: LibraryFolder(LibraryFolder.key(serverID, path), serverID, path, title)
                                        .also { dao.upsertFolder(it) }
                                    AppGraph.scanner.scanInBackground(folder)
                                }
                            }
                            .glassPanel(12.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (libraryFolder != null) Icons.Filled.Sync else Icons.Filled.LibraryAdd, null, tint = XtColors.leaf)
                        Spacer(Modifier.width(10.dp))
                        Text(if (libraryFolder != null) "Đã có trong thư viện — quét lại" else "Thêm thư mục này vào thư viện")
                    }
                    if (scanning || status.isNotEmpty() || scanError != null) {
                        Text(
                            scanError ?: status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (scanError != null) XtColors.danger else XtColors.secondaryText,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        when {
            loading -> item {
                Row(Modifier.padding(horizontal = dimens.pagePadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Đang tải…", color = XtColors.secondaryText)
                }
            }
            error != null -> item {
                Column(Modifier.padding(horizontal = dimens.pagePadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = XtColors.danger)
                        Spacer(Modifier.width(8.dp))
                        Text(error.orEmpty(), color = XtColors.danger)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { reload++ }) { Text("Thử lại") }
                }
            }
            listing.isEmpty() -> item { EmptyText("Thư mục trống") }
        }
        items(directories, key = { "d:" + it.path }) { dir ->
            BrowserRow(
                title = dir.name,
                detail = null,
                leading = {
                    Icon(if (path.isEmpty()) Icons.Filled.Dns else Icons.Filled.Folder, null, tint = XtColors.leaf, modifier = Modifier.size(28.dp))
                },
            ) { actions.openBrowser(serverID, dir.path, dir.name) }
        }
        items(videos, key = { "v:" + it.path }) { video ->
            BrowserRow(
                title = video.name,
                detail = (listOf(ByteFormat.file(video.size)) + FilenameParser.parse(video.name).badges).joinToString(" · "),
                leading = { server?.let { BrowserThumbnail(it, video) } },
            ) {
                val credentials = server ?: return@BrowserRow
                actions.scope.launch {
                    val queue = PlaybackFactory.remote(videos, subtitles, credentials)
                    val start = videos.indexOf(video).coerceAtLeast(0)
                    if (queue.isNotEmpty()) actions.play(PlaybackSession(queue, start))
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(title: String, detail: String?, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val dimens = LocalDimens.current
    Row(
        Modifier
            .padding(horizontal = dimens.pagePadding)
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.02f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 1)
        }
    }
}

/** A frame of the video, grabbed when the row comes on screen (two at a time) and kept for next time. */
@Composable
private fun BrowserThumbnail(server: ServerCredentials, item: RemoteItem) {
    val key = remember(server.id, item.path) { RemoteFileSystems.key(server, item.path) }
    val file = remember(key) { ThumbnailStore.file(ThumbnailStore.reference(key)) }
    var ready by remember(key) { mutableStateOf(file?.exists() == true) }
    LaunchedEffect(key) {
        if (!ready && file != null) {
            ServerRegistry.register(server)
            ready = FrameGrabber.grab(key, file) != null
        }
    }
    Box(
        Modifier
            .width(128.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(XtColors.surfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (ready && file != null) {
            AsyncImage(model = file, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.PlayCircle, null, tint = XtColors.leaf.copy(alpha = 0.8f))
        }
    }
}

/** Add a NAS: servers found over mDNS first, then the form; the connection is tested before saving. */
@Composable
fun AddServerScreen() {
    val context = LocalContext.current
    val actions = LocalAppActions.current
    val scope = rememberCoroutineScope()
    val dimens = LocalDimens.current
    val discovery = remember { ServerDiscovery(context) }
    val found by discovery.servers.collectAsState()
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    var kind by remember { mutableStateOf(ServerKind.SMB) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var basePath by remember { mutableStateOf("/") }
    var useTLS by remember { mutableStateOf(false) }
    var allowInsecure by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failedOnce by remember { mutableStateOf(false) }

    fun pick(server: DiscoveredServer) {
        kind = server.kind
        name = server.name
        useTLS = server.secure
        scope.launch { host = discovery.resolveHost(server) }
    }

    fun save(force: Boolean) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            message = "Nhập địa chỉ máy chủ."
            return
        }
        var cleanedPath = basePath.trim().ifEmpty { "/" }
        if (!cleanedPath.startsWith("/")) cleanedPath = "/$cleanedPath"
        val server = MediaServer(
            name = name.trim().ifEmpty { trimmedHost }, kindRaw = kind.raw, host = trimmedHost, port = port.trim().toIntOrNull() ?: 0,
            username = username.trim(), domain = domain.trim(), useTLS = useTLS, basePath = cleanedPath, allowInsecureTLS = allowInsecure,
        )
        scope.launch {
            server.setPassword(password)
            if (!force) {
                testing = true
                message = null
                try {
                    val root = RemoteFileSystems.make(server.credentials).list("")
                    message = "Kết nối thành công — ${root.size} mục."
                } catch (e: Exception) {
                    testing = false
                    failedOnce = true
                    message = "Không kết nối được: ${e.message ?: e}"
                    RemoteFileSystemPool.reset(server.id)
                    AppGraph.secrets.set("server.${server.id}", null)
                    return@launch
                }
                testing = false
            }
            AppGraph.dao.upsertServer(server)
            ServerRegistry.register(server.credentials)
            actions.back()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 40.dp)) {
        item { PageTitle("Thêm máy chủ", inset = false) }
        if (found.isNotEmpty()) {
            item { FormLabel("Tìm thấy trong mạng") }
            items(found, key = { it.id }) { server ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.02f) { pick(server) }
                        .glassPanel(12.dp)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (server.kind == ServerKind.SMB) Icons.Filled.Dns else Icons.Filled.Language, null, tint = XtColors.leaf)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.name)
                        Text(server.kind.label + if (server.secure) " (HTTPS)" else "", style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
                    }
                }
            }
        }
        item { FormLabel("Loại máy chủ") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerKind.entries.forEach { option -> SortChip(option.label, option == kind) { kind = option } }
            }
        }
        item { FormLabel("Địa chỉ") }
        item { FormField("Tên hiển thị", name) { name = it } }
        item { FormField("Địa chỉ IP hoặc tên máy (vd 192.168.1.10, nas.local)", host, KeyboardType.Uri) { host = it } }
        item { FormField(if (kind == ServerKind.SMB) "Cổng (để trống = 445)" else "Cổng (để trống = 80 / 443)", port, KeyboardType.Number) { port = it } }
        if (kind == ServerKind.WEBDAV) {
            item { FormField("Đường dẫn gốc (vd /dav/media)", basePath, KeyboardType.Uri) { basePath = it } }
            item { SwitchRow("HTTPS", useTLS) { useTLS = it } }
            item { SwitchRow("Chấp nhận chứng chỉ tự ký", allowInsecure) { allowInsecure = it } }
        }
        item { FormLabel("Đăng nhập") }
        item { FormField("Tài khoản (để trống = khách)", username) { username = it } }
        item { FormField("Mật khẩu", password, KeyboardType.Password, secret = true) { password = it } }
        if (kind == ServerKind.SMB) {
            item { FormField("Domain / Workgroup (không bắt buộc)", domain) { domain = it } }
        }
        item {
            Column(Modifier.padding(top = 18.dp)) {
                message?.let {
                    Text(it, color = if (failedOnce) XtColors.danger else XtColors.leaf, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { save(force = false) }, enabled = !testing) { Text(if (testing) "Đang kiểm tra…" else "Kiểm tra & lưu") }
                    if (failedOnce) OutlinedButton(onClick = { save(force = true) }) { Text("Vẫn lưu") }
                    if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Mật khẩu được mã hoá bằng Android Keystore. SMB: hỗ trợ SMB2 / SMB3 (Windows, Synology, QNAP, TrueNAS…).",
                    style = MaterialTheme.typography.bodySmall,
                    color = XtColors.secondaryText,
                )
            }
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = XtColors.leaf, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
fun FormField(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.01f) { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
