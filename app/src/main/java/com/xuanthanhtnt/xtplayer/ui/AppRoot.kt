package com.xuanthanhtnt.xtplayer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xuanthanhtnt.xtplayer.AppGraph
import com.xuanthanhtnt.xtplayer.R
import com.xuanthanhtnt.xtplayer.core.ByteFormat
import com.xuanthanhtnt.xtplayer.player.PlaybackFactory
import com.xuanthanhtnt.xtplayer.player.PlaybackSession
import com.xuanthanhtnt.xtplayer.player.PlayerLauncher
import com.xuanthanhtnt.xtplayer.ui.detail.DetailScreen
import com.xuanthanhtnt.xtplayer.ui.home.HomeScreen
import com.xuanthanhtnt.xtplayer.ui.library.GenresScreen
import com.xuanthanhtnt.xtplayer.ui.library.LibraryFilter
import com.xuanthanhtnt.xtplayer.ui.library.LibraryGridScreen
import com.xuanthanhtnt.xtplayer.ui.library.SearchScreen
import com.xuanthanhtnt.xtplayer.ui.settings.SettingsScreen
import com.xuanthanhtnt.xtplayer.ui.sources.AddServerScreen
import com.xuanthanhtnt.xtplayer.ui.sources.BrowserScreen
import com.xuanthanhtnt.xtplayer.ui.sources.SourcesScreen
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors
import com.xuanthanhtnt.xtplayer.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Top-level sections: a sidebar on TV and tablets (grouped like Infuse's), a bottom bar on phones. */
enum class Section(val route: String, val label: String, val icon: ImageVector, val onPhone: Boolean) {
    SEARCH("search", "Tìm kiếm", Icons.Filled.Search, false),
    HOME("home", "Trang chủ", Icons.Filled.Home, true),
    MOVIES("movies", "Phim", Icons.Filled.Movie, true),
    SHOWS("shows", "TV Shows", Icons.Filled.Tv, true),
    RECENT("recent", "Mới thêm", Icons.Filled.History, false),
    UNWATCHED("unwatched", "Chưa xem", Icons.Filled.VisibilityOff, false),
    TOP_RATED("toprated", "Điểm cao", Icons.Filled.Star, false),
    GENRES("genres", "Thể loại", Icons.Filled.TheaterComedy, false),
    SOURCES("sources", "Nguồn", Icons.Filled.Storage, true),
    SETTINGS("settings", "Cài đặt", Icons.Filled.Settings, true),
}

/** Navigation and playback, available to every screen (the SwiftUI environment actions of the iOS app). */
class AppActions(
    private val nav: NavHostController,
    private val context: Context,
    val scope: CoroutineScope,
    val pickFile: () -> Unit,
    val openUrl: () -> Unit,
) {
    fun openSection(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openDetail(itemKey: String) = nav.navigate("detail/${encode(itemKey)}")
    fun openGrid(filter: LibraryFilter) = nav.navigate("grid/${filter.name}")
    fun openBrowser(serverID: String, path: String, title: String) =
        nav.navigate("browser/${encode(serverID)}?path=${encode(path)}&title=${encode(title)}")

    fun addServer() = nav.navigate("addServer")
    fun back() = nav.popBackStack()
    fun play(session: PlaybackSession) = PlayerLauncher.play(context, session)

    companion object {
        /** Route arguments travel as URL-safe Base64: keys and paths contain '/', ':', '%', '?'. */
        fun encode(text: String): String =
            Base64.encodeToString(text.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).ifEmpty { "-" }

        fun decode(text: String?): String =
            if (text.isNullOrEmpty() || text == "-") "" else runCatching {
                String(Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            }.getOrDefault("")
    }
}

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

@Composable
fun XtApp(isTv: Boolean, wide: Boolean) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showUrl by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { PlayerLauncher.play(context, PlaybackSession(listOf(PlaybackFactory.local(context, uri)))) }
        }
    }
    val actions = remember(nav) {
        AppActions(nav, context, scope, pickFile = {
            runCatching { picker.launch(arrayOf("video/*", "application/x-matroska", "application/octet-stream")) }
        }, openUrl = { showUrl = true })
    }

    LaunchedEffect(Unit) { AppGraph.updates.checkIfDue() }

    CompositionLocalProvider(LocalAppActions provides actions) {
        if (wide || isTv) {
            Row(
                Modifier
                    .fillMaxSize()
                    .appBackground()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                SideBar(nav, isTv)
                Box(Modifier.weight(1f).fillMaxHeight()) { AppNavHost(nav) }
            }
        } else {
            Scaffold(
                containerColor = XtColors.background,
                bottomBar = { BottomBar(nav) },
            ) { padding ->
                Box(Modifier.fillMaxSize().appBackground().padding(padding)) { AppNavHost(nav) }
            }
        }
        if (showUrl) {
            OpenUrlDialog(onDismiss = { showUrl = false }) { text ->
                scope.launch {
                    val item = PlaybackFactory.url(text)
                    if (item != null) {
                        showUrl = false
                        actions.play(PlaybackSession(listOf(item)))
                    }
                }
            }
        }
        UpdatePrompt()
    }
}

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(nav, startDestination = Section.HOME.route, modifier = Modifier.fillMaxSize()) {
        composable(Section.HOME.route) { HomeScreen() }
        composable(Section.SEARCH.route) { SearchScreen() }
        composable(Section.MOVIES.route) { LibraryGridScreen(LibraryFilter.MOVIES) }
        composable(Section.SHOWS.route) { LibraryGridScreen(LibraryFilter.SHOWS) }
        composable(Section.RECENT.route) { LibraryGridScreen(LibraryFilter.RECENT) }
        composable(Section.UNWATCHED.route) { LibraryGridScreen(LibraryFilter.UNWATCHED) }
        composable(Section.TOP_RATED.route) { LibraryGridScreen(LibraryFilter.TOP_RATED) }
        composable(Section.GENRES.route) { GenresScreen() }
        composable(Section.SOURCES.route) { SourcesScreen() }
        composable(Section.SETTINGS.route) { SettingsScreen() }
        composable("grid/{filter}") { entry ->
            val filter = entry.arguments?.getString("filter")?.let { name -> LibraryFilter.entries.firstOrNull { it.name == name } }
            LibraryGridScreen(filter ?: LibraryFilter.MOVIES)
        }
        composable("detail/{key}") { entry -> DetailScreen(AppActions.decode(entry.arguments?.getString("key"))) }
        composable(
            "browser/{server}?path={path}&title={title}",
            arguments = listOf(
                navArgument("server") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "-" },
                navArgument("title") { type = NavType.StringType; defaultValue = "-" },
            ),
        ) { entry ->
            val args = entry.arguments
            BrowserScreen(
                serverID = AppActions.decode(args?.getString("server")),
                path = AppActions.decode(args?.getString("path")),
                title = AppActions.decode(args?.getString("title")),
            )
        }
        composable("addServer") { AddServerScreen() }
    }
}

@Composable
private fun SideBar(nav: NavHostController, isTv: Boolean) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val actions = LocalAppActions.current
    Column(
        Modifier
            .width(if (isTv) 220.dp else 210.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.25f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.brand_mark), null, Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Text("XT-Player", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        }
        for (section in Section.entries) {
            if (section == Section.RECENT) SideHeader("Thư viện")
            if (section == Section.SOURCES) SideHeader("Tệp")
            SideItem(section, selected = route == section.route) { actions.openSection(section.route) }
        }
    }
}

@Composable
private fun SideHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = XtColors.secondaryText,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SideItem(section: Section, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(12.dp), focusScale = 1.03f, onClick = onClick)
            .background(if (selected) XtColors.leaf.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, null, tint = if (selected) XtColors.lime else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(section.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val actions = LocalAppActions.current
    NavigationBar(containerColor = XtColors.surface) {
        for (section in Section.entries.filter { it.onPhone }) {
            NavigationBarItem(
                selected = route == section.route,
                onClick = { actions.openSection(section.route) },
                icon = { Icon(section.icon, section.label) },
                label = { Text(section.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.Black,
                    indicatorColor = XtColors.leaf,
                    selectedTextColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun OpenUrlDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mở URL / NAS") },
        text = {
            Column {
                Text(
                    "Dán đường dẫn trực tiếp tới tệp phim, ví dụ http://192.168.1.10:5005/Movies/Dune.2160p.DV.mkv",
                    style = MaterialTheme.typography.bodySmall,
                    color = XtColors.secondaryText,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(text, { text = it; invalid = false }, singleLine = true, placeholder = { Text("https://…") })
                if (invalid) Text("URL chưa hợp lệ. Hỗ trợ: http, https.", color = XtColors.danger, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val candidate = text.trim().let { if (it.contains("://")) it else "http://$it" }
                val scheme = Uri.parse(candidate).scheme
                if (text.isBlank() || (scheme != "http" && scheme != "https")) invalid = true else onSubmit(text)
            }) { Text("Phát") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

/** "Có bản mới" — asked once per version unless the user checks by hand. */
@Composable
private fun UpdatePrompt() {
    val updates = AppGraph.updates
    val release by updates.prompt.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = release ?: return
    AlertDialog(
        onDismissRequest = updates::dismissPrompt,
        title = { Text("Có XT-Player ${current.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Bạn đang dùng ${UpdateChecker.currentVersion}." + if (current.downloadSize > 0) " Tệp cài đặt ${ByteFormat.file(current.downloadSize)}." else "")
                if (current.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(current.notes.take(1200), style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText)
                }
                if (!updates.canInstall(context)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cần cho phép XT-Player cài ứng dụng: Cài đặt ▸ Cài ứng dụng không rõ nguồn gốc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = XtColors.lime,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!updates.canInstall(context)) updates.openInstallPermission(context)
                else scope.launch { updates.install(context, current) }
            }) { Text(if (updates.canInstall(context)) "Cập nhật" else "Cho phép") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { updates.skip(current) }) { Text("Bỏ qua bản này") }
                TextButton(onClick = updates::dismissPrompt) { Text("Để sau") }
            }
        },
    )
}
