package com.xuanthanhtnt.xtplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.BuildConfig
import com.xuanthanhtnt.xtplayer.core.Pref
import com.xuanthanhtnt.xtplayer.data.MediaLibrary
import com.xuanthanhtnt.xtplayer.trakt.TraktService
import com.xuanthanhtnt.xtplayer.ui.LocalAppActions
import com.xuanthanhtnt.xtplayer.ui.PageTitle
import com.xuanthanhtnt.xtplayer.ui.focusClickable
import com.xuanthanhtnt.xtplayer.ui.glassPanel
import com.xuanthanhtnt.xtplayer.ui.sources.FormField
import com.xuanthanhtnt.xtplayer.ui.sources.SwitchRow
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import com.xuanthanhtnt.xtplayer.update.UpdateChecker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val settings = AppGraph.settings
    val dimens = LocalDimens.current
    val actions = LocalAppActions.current
    val dao = AppGraph.dao
    val servers by remember { dao.serversFlow() }.collectAsState(initial = emptyList())
    val folders by remember { dao.foldersFlow() }.collectAsState(initial = emptyList())
    val scanning by AppGraph.scanner.isScanning.collectAsState()
    val scanStatus by AppGraph.scanner.status.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = 40.dp),
    ) {
        item { PageTitle("Cài đặt", inset = false) }

        section("Phát lại", "Áp dụng cho lần phát tiếp theo.") {
            PrefSwitch(
                "Giải mã phần cứng", settings.hardwareDecoding,
                "Bật (khuyên dùng): dùng chip giải mã của máy — mượt, mát, giữ HDR / Dolby Vision. Tắt khi một tệp bị sọc, " +
                    "xanh lá hay giật hình: dùng bộ giải mã phần mềm của Android. Đổi nhanh trong trình phát ▸ ⚙︎ ▸ Video.",
            )
            ChoiceRow(
                "Khớp tần số quét", settings.frameRateMatching,
                listOf("off" to "Tắt", "seamless" to "Khi chuyển mượt", "always" to "Luôn đổi (Match Content)"),
                detail = "Android TV: đổi tần số quét của TV theo video (23.976 / 24 / 25 / 50 / 60 Hz) — hết giật hình khi máy quay ngang; " +
                    "Match Content còn chuyển TV sang 4K cho phim 4K khi hộp đang xuất 1080p.",
            )
            ChoiceRow("Bộ đệm RAM", settings.cacheMegabytes, listOf(100, 200, 300, 500, 800, 1200).map { it to "$it MB" })
            PrefSwitch("Tự phát tập tiếp theo", settings.autoplayNext)
            ChoiceRow("Tua lùi", settings.skipBackSeconds, listOf(5, 10, 15, 30).map { it to "$it giây" })
            ChoiceRow("Tua tới", settings.skipForwardSeconds, listOf(10, 15, 30, 60).map { it to "$it giây" })
            PrefSwitch("Chế độ tunneled", settings.tunneling, "Một số Android TV đồng bộ hình / tiếng tốt hơn khi bitstream. Tắt nếu bị đen hình.")
        }

        section("Âm thanh", "Bitstream chỉ có tác dụng khi ampli / TV qua HDMI nhận được định dạng đó; lúc bitstream, âm lượng chỉnh trên ampli.") {
            PrefSwitch("Bitstream TrueHD / DTS-HD / E-AC3 qua HDMI", settings.audioPassthrough)
            PrefText("Ngôn ngữ âm thanh ưu tiên (vd: vie,eng)", settings.audioLanguages)
        }

        section("Phụ đề", null) {
            PrefText("Ngôn ngữ phụ đề ưu tiên", settings.subtitleLanguages)
            ChoiceRow("Cỡ chữ", settings.subtitleScale, listOf(0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.5f).map { it to "${(it * 100).toInt()}%" })
            ChoiceRow("Vị trí", settings.subtitlePosition, listOf(100 to "Sát đáy", 94 to "Cao hơn", 60 to "Giữa màn hình"))
            PrefSwitch("Nền tối sau chữ", settings.subtitleBackground)
        }

        section("Thư viện", "Lấy khoá miễn phí tại themoviedb.org ▸ Settings ▸ API. ${folders.size} thư mục trong thư viện.") {
            SecretField("TMDb API key (v3) hoặc Read Access Token (v4)", "tmdb")
            ChoiceRow("Ngôn ngữ thông tin phim", settings.metadataLanguage, listOf("vi-VN" to "Tiếng Việt", "en-US" to "English"))
            Button(
                onClick = { AppGraph.scanner.scanAllInBackground() },
                enabled = !scanning && folders.isNotEmpty(),
                modifier = Modifier.padding(vertical = 6.dp),
            ) { Text(if (scanning) scanStatus.ifEmpty { "Đang quét…" } else "Quét lại toàn bộ thư viện") }
        }

        section("Máy chủ", if (servers.isEmpty()) "Chưa có máy chủ — thêm ở mục Nguồn." else null) {
            servers.forEach { server ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${server.kind.label} · ${server.displayHost}", style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
                    }
                    OutlinedButton(onClick = { actions.scope.launch { MediaLibrary.removeServer(server) } }) {
                        Icon(Icons.Filled.Delete, null)
                        Text("Xoá")
                    }
                }
            }
        }

        section(
            "Phụ đề trực tuyến (OpenSubtitles)",
            "Lấy API key miễn phí tại opensubtitles.com ▸ Profile ▸ API Consumers. Đăng nhập tài khoản để được tải nhiều phụ đề hơn mỗi ngày. Tìm phụ đề trong trình phát ▸ bảng Phụ đề.",
        ) {
            SecretField("API key", "opensubtitles")
            PrefText("Tài khoản (không bắt buộc)", settings.openSubtitlesUser)
            SecretField("Mật khẩu", "opensubtitles.password")
        }

        section("Trakt", null) { TraktSection() }

        section("Cập nhật", "Bản cập nhật lấy từ GitHub Releases (${BuildConfig.UPDATE_REPOSITORY}).") { UpdateSection() }

        section("Nhật ký lỗi", "Nếu XT-Player bị dừng đột ngột, gửi nhật ký này để sửa lỗi.") { CrashSection() }

        section("Giới thiệu", null) {
            InfoRow("Phiên bản", BuildConfig.VERSION_NAME)
            InfoRow("Lõi phát", "Media3 ExoPlayer 1.8 · FFmpeg (Jellyfin) · MediaCodec")
            InfoRow("SMB", "smbj (SMB2 / SMB3)")
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, footer: String?, content: @Composable () -> Unit) {
    item {
        Column(
            Modifier
                .padding(top = 18.dp)
                .widthIn(max = 900.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = XtColors.leaf, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassPanel(16.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { content() }
            if (footer != null) {
                Text(footer, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
        }
    }
}

/** A setting with a few values: OK / tap cycles to the next one (works the same with a TV remote and touch). */
@Composable
private fun <T> ChoiceRow(label: String, pref: Pref<T>, options: List<Pair<T, String>>, detail: String? = null) {
    val value by pref.collect()
    val currentIndex = options.indexOfFirst { it.first == value }
    Row(
        Modifier
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(10.dp), focusScale = 1.01f) {
                pref.value = options[(currentIndex + 1).mod(options.size)].first
            }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
        }
        Text(options.getOrNull(currentIndex)?.second ?: "$value", color = XtColors.lime, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PrefSwitch(label: String, pref: Pref<Boolean>, detail: String? = null) {
    val value by pref.collect()
    SwitchRow(label, value, detail) { pref.value = it }
}

@Composable
private fun PrefText(label: String, pref: Pref<String>) {
    val value by pref.collect()
    FormField(label, value) { pref.value = it }
}

/** A key or password stored encrypted (SecureStore), saved with its own button. */
@Composable
private fun SecretField(label: String, account: String) {
    var text by remember { mutableStateOf(AppGraph.secrets.get(account).orEmpty()) }
    var saved by remember { mutableStateOf(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FormField(label, text, secret = true) {
                text = it
                saved = false
            }
        }
        Spacer(Modifier.padding(4.dp))
        OutlinedButton(onClick = {
            AppGraph.secrets.set(account, text.trim())
            saved = true
        }, enabled = !saved) { Text(if (saved) "Đã lưu" else "Lưu") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = XtColors.secondaryText)
    }
}

/**
 * Trakt sign-in with the user's own account: press "Kết nối Trakt", open trakt.tv/activate on any device, enter the
 * code. The Trakt application (Client ID) is built into the app; a personal one can still be set under "Nâng cao".
 */
@Composable
private fun TraktSection() {
    val trakt = AppGraph.trakt
    val phase by trakt.phase.collectAsState()
    val username by trakt.username.collectAsState()
    val status by trakt.status.collectAsState()
    val syncing by trakt.isSyncing.collectAsState()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var advanced by remember { mutableStateOf(false) }
    var hasClient by remember { mutableStateOf(trakt.hasAppCredentials) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val p = phase) {
            TraktService.Phase.Disconnected -> {
                Text(
                    "Đăng nhập bằng tài khoản Trakt để đồng bộ phim đã xem và điểm xem tiếp với các thiết bị khác.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = {
                    hasClient = trakt.hasAppCredentials
                    if (hasClient) trakt.startLogin() else advanced = true
                }) { Text("Kết nối Trakt") }
            }
            is TraktService.Phase.AwaitingCode -> {
                Text("Trên điện thoại hoặc máy tính, mở ${p.url}, đăng nhập Trakt rồi nhập mã:", style = MaterialTheme.typography.bodyMedium)
                Text(p.code, style = MaterialTheme.typography.displaySmall, color = XtColors.lime, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        copyText(context, p.code)
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Mở trang kích hoạt") }
                    OutlinedButton(onClick = { copyText(context, p.code) }) { Text("Sao chép mã") }
                    OutlinedButton(onClick = trakt::cancelLogin) { Text("Huỷ") }
                }
                Text("XT-Player tự kết nối khi bạn nhập mã xong.", style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
            }
            TraktService.Phase.Connected -> {
                Text("Đã kết nối" + if (username.isNotEmpty()) " — $username" else "", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { actions.scope.launch { trakt.sync() } }, enabled = !syncing) { Text(if (syncing) "Đang đồng bộ…" else "Đồng bộ ngay") }
                    OutlinedButton(onClick = trakt::disconnect) { Text("Ngắt kết nối") }
                }
                PrefSwitch("Scrobble khi xem", AppGraph.settings.traktScrobble)
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
        if (!hasClient && advanced) {
            Text(
                "Bản cài này chưa có sẵn ứng dụng Trakt. Tạo một lần tại trakt.tv/oauth/applications/new (Redirect URI: http://localhost), " +
                    "dán Client ID dưới đây, Lưu, rồi bấm Kết nối Trakt.",
                style = MaterialTheme.typography.bodySmall,
                color = XtColors.lime,
            )
        }
        Text(
            if (advanced) "Ẩn nâng cao" else "Nâng cao: ứng dụng Trakt riêng",
            style = MaterialTheme.typography.labelLarge,
            color = XtColors.leaf,
            modifier = Modifier
                .focusClickable(RoundedCornerShape(8.dp), focusScale = 1.03f) { advanced = !advanced }
                .padding(vertical = 6.dp, horizontal = 4.dp),
        )
        if (advanced) {
            SecretField("Client ID (Mã khách hàng)", "trakt.clientID")
            SecretField("Client Secret (để trống nếu không có)", "trakt.clientSecret")
        }
    }
}

private fun copyText(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Trakt", text))
}

/** The last crash report (also offered on the home screen right after a crash). */
@Composable
private fun CrashSection() {
    var report by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { report = com.xuanthanhtnt.xtplayer.core.CrashLog.last() ?: "Chưa có lỗi nào được ghi lại." }) {
            Text("Xem nhật ký lỗi gần nhất")
        }
    }
    report?.let { text ->
        com.xuanthanhtnt.xtplayer.ui.CrashReportDialog(text, title = "Nhật ký lỗi") { report = null }
    }
}

@Composable
private fun UpdateSection() {
    val updates = AppGraph.updates
    val phase by updates.phase.collectAsState()
    val latest by updates.latest.collectAsState()
    val context = LocalContext.current
    val actions = LocalAppActions.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoRow("Phiên bản đang dùng", UpdateChecker.currentVersion)
        when (val p = phase) {
            UpdateChecker.Phase.Checking -> Text("Đang kiểm tra…", color = XtColors.secondaryText)
            UpdateChecker.Phase.UpToDate -> Text("Bạn đang dùng bản mới nhất.", color = XtColors.secondaryText)
            is UpdateChecker.Phase.Failed -> Text(p.message, color = XtColors.danger)
            is UpdateChecker.Phase.Downloading -> {
                Text("Đang tải bản cập nhật… ${(p.fraction * 100).toInt()}%")
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            }
            else -> Unit
        }
        val release = latest
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { actions.scope.launch { updates.check(userInitiated = true) } }) { Text("Kiểm tra cập nhật") }
            if (release != null && UpdateChecker.isNewer(release.version, UpdateChecker.currentVersion)) {
                Button(onClick = {
                    if (!updates.canInstall(context)) updates.openInstallPermission(context)
                    else actions.scope.launch { updates.install(context, release) }
                }) { Text("Cài bản ${release.version}") }
            }
        }
        Text(
            "Lần đầu cài cập nhật, Android sẽ hỏi quyền \"Cài ứng dụng không rõ nguồn gốc\" cho XT-Player.",
            style = MaterialTheme.typography.bodySmall,
            color = XtColors.secondaryText,
        )
    }
}
