package com.xuanthanhtnt.xtplayer.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.xuanthanhtnt.xtplayer.ui.settings.SettingsGroup
import com.xuanthanhtnt.xtplayer.ui.settings.SettingsGroupContent
import com.xuanthanhtnt.xtplayer.ui.settings.settingsFooter
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors

private data class TvItem(val section: Section, val title: String, val icon: ImageVector)

/** TVShell.swift `items`: the sections of the TV menu. */
private val tvItems = listOf(
    TvItem(Section.HOME, "Trang chủ", Icons.Filled.Home),
    TvItem(Section.MOVIES, "Phim", Icons.Filled.Movie),
    TvItem(Section.SHOWS, "TV Shows", Icons.Filled.Tv),
    TvItem(Section.MUSIC, "Nhạc", Icons.Filled.LibraryMusic),
    TvItem(Section.MUSIC_VIDEOS, "Video ca nhạc", Icons.Filled.MusicVideo),
    TvItem(Section.SOURCES, "Nguồn", Icons.Filled.Storage),
)

/** 400 and 760 pt on the Apple TV's 1920 pt screen. */
private val MenuWidth = 200.dp
private val PanelWidth = 380.dp

/**
 * Android TV navigation (TVShell.swift). The menu is hidden and the page fills the screen; left at the page's left
 * edge (when the focus cannot move further left), or Back at the root of a section, brings the menu in, on the
 * section being shown, and shrinks the page to make room. Moving through the menu shows each section; picking one or
 * pressing right puts the page back; Back in the menu leaves the app. "Cài đặt" opens a second level in the menu
 * itself: its groups, and the group under the focus shown in a panel beside the menu (no Settings page).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvShell(nav: NavHostController) {
    val actions = LocalAppActions.current
    val activity = LocalContext.current as? Activity
    val focusManager = LocalFocusManager.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var shown by rememberSaveable { mutableStateOf(Section.HOME) }
    LaunchedEffect(route) { tvItems.firstOrNull { it.section.route == route }?.let { shown = it.section } }
    val atSectionRoot = route == null || tvItems.any { it.section.route == route }

    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsGroup by remember { mutableStateOf<SettingsGroup?>(null) }
    var panelFocused by remember { mutableStateOf(false) }
    val sectionRows = remember { tvItems.associate { it.section to FocusRequester() } }
    val settingsRow = remember { FocusRequester() }
    val groupRows = remember { SettingsGroup.entries.associateWith { FocusRequester() } }
    val panelFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }

    fun openMenu() {
        menuOpen = true
    }

    fun closeMenu() {
        menuOpen = false
        settingsOpen = false
        settingsGroup = null
    }

    // Opening puts the focus on the section being shown (not on the row nearest the page's focus); closing hands
    // it back to the page. Nothing here opens the menu at launch or when a page is pushed.
    LaunchedEffect(menuOpen) {
        withFrameNanos { }
        runCatching { if (menuOpen) sectionRows[shown]?.requestFocus() else pageFocus.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(XtColors.background)) {
        // The page, scaled from its right edge, ends up just right of the open menu.
        val shrink = ((maxWidth - MenuWidth - 15.dp) / maxWidth).coerceIn(0.5f, 1f)
        val springy = spring<Float>(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)
        val scale by animateFloatAsState(if (menuOpen) shrink else 1f, springy, label = "pageScale")
        val corner by animateDpAsState(if (menuOpen) 20.dp else 0.dp, label = "pageCorner")
        // Behind the settings panel the page steps further back.
        val dim by animateFloatAsState(
            when {
                settingsGroup != null -> 0.4f
                menuOpen -> 0.2f
                else -> 0f
            },
            label = "pageDim",
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0.5f)
                    shape = RoundedCornerShape(corner)
                    clip = corner > 0.dp
                }
                .then(if (settingsGroup != null && Build.VERSION.SDK_INT >= 31) Modifier.blur(7.dp) else Modifier)
                .focusRequester(pageFocus)
                // The page takes no focus while the menu is open.
                .focusProperties { enter = { if (menuOpen) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup()
                .onKeyEvent { event ->
                    // Left that cannot go further left inside the page opens the menu (up and down never do).
                    if (menuOpen || event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft) return@onKeyEvent false
                    if (!focusManager.moveFocus(FocusDirection.Left)) openMenu()
                    true
                }
        ) {
            AppNavHost(nav, sidebar = true)
            if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }

        val group = settingsGroup
        AnimatedVisibility(
            visible = settingsOpen && group != null,
            enter = slideInHorizontally { -it / 4 } + fadeIn(),
            exit = fadeOut(),
        ) {
            if (group != null) {
                SettingsPanel(
                    group,
                    Modifier
                        .padding(start = MenuWidth + 10.dp, top = 20.dp, bottom = 20.dp)
                        .focusRequester(panelFocus)
                        .onFocusChanged { panelFocused = it.hasFocus }
                        .focusGroup(),
                )
            }
        }

        AnimatedVisibility(
            visible = menuOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            Column(
                Modifier
                    .width(MenuWidth)
                    .fillMaxHeight()
                    .background(XtColors.background.copy(alpha = 0.94f))
                    // Up and down stop at the ends; right is handled by the rows.
                    .focusProperties { exit = { direction -> if (direction == FocusDirection.Right && settingsGroup != null) FocusRequester.Default else FocusRequester.Cancel } }
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    Modifier.padding(start = 7.dp, bottom = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Filled.PlayCircleFilled, null, tint = XtColors.leaf, modifier = Modifier.size(18.dp))
                    Text("XT Player", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
                }
                tvItems.forEach { item ->
                    MenuRow(
                        item.title, item.icon,
                        selected = shown == item.section,
                        modifier = Modifier
                            .focusRequester(sectionRows.getValue(item.section))
                            .onFocusChanged {
                                if (!it.isFocused) return@onFocusChanged
                                // As in Apple's own apps, the section under the focus is shown while moving through the menu.
                                if (route != item.section.route) actions.openSection(item.section.route)
                                settingsOpen = false
                                settingsGroup = null
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    closeMenu()
                                    true
                                } else {
                                    false
                                }
                            },
                    ) { closeMenu() }
                }
                Spacer(Modifier.height(9.dp))
                MenuRow(
                    "Cài đặt", Icons.Filled.Settings,
                    selected = settingsOpen,
                    trailing = if (settingsOpen) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    modifier = Modifier
                        .focusRequester(settingsRow)
                        .onFocusChanged { if (it.isFocused) settingsGroup = null },
                ) {
                    settingsOpen = !settingsOpen
                    if (!settingsOpen) settingsGroup = null
                }
                if (settingsOpen) {
                    SettingsGroup.entries.forEach { g ->
                        MenuRow(
                            g.title, g.icon,
                            selected = settingsGroup == g,
                            indent = true,
                            trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            modifier = Modifier
                                .focusRequester(groupRows.getValue(g))
                                .onFocusChanged { if (it.isFocused) settingsGroup = g }
                                .onPreviewKeyEvent { event ->
                                    // Right goes into the group's panel.
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                        runCatching { panelFocus.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                },
                        ) {
                            settingsGroup = g
                            runCatching { panelFocus.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    // Composed after the page, so these come before its navigation when they are enabled.
    BackHandler(enabled = !menuOpen && atSectionRoot) { openMenu() }
    BackHandler(enabled = menuOpen && panelFocused) {
        // Back from the panel returns to its group in the menu.
        settingsGroup?.let { runCatching { groupRows.getValue(it).requestFocus() } }
    }
    BackHandler(enabled = menuOpen && !panelFocused && settingsOpen) {
        settingsOpen = false
        settingsGroup = null
        runCatching { settingsRow.requestFocus() }
    }
    BackHandler(enabled = menuOpen && !panelFocused && !settingsOpen) { activity?.finish() }
}

@Composable
private fun MenuRow(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    indent: Boolean = false,
    trailing: ImageVector? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "rowScale")
    val tint = when {
        focused -> Color.Black
        selected -> XtColors.leaf
        else -> Color.White.copy(alpha = 0.85f)
    }
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    selected && !indent -> Color.White.copy(alpha = 0.1f)
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = if (indent) 23.dp else 8.dp, end = 8.dp, top = if (indent) 5.dp else 7.dp, bottom = if (indent) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(if (indent) 16.dp else 19.dp))
        Text(
            title,
            style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Icon(trailing, null, tint = tint.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
    }
}

/** TVShell.settingsPanel: the group under the focus, its options listed in place. */
@Composable
private fun SettingsPanel(group: SettingsGroup, modifier: Modifier) {
    Column(
        modifier
            .width(PanelWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF2141A17))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 15.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(group.icon, null, tint = XtColors.leaf, modifier = Modifier.size(18.dp))
            Text(group.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
        Spacer(Modifier.height(10.dp))
        SettingsGroupContent(group)
        settingsFooter(group)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
