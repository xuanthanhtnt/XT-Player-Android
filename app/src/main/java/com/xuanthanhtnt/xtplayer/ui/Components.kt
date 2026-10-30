package com.xuanthanhtnt.xtplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.xuanthanhtnt.xtplayer.data.LibraryItem
import com.xuanthanhtnt.xtplayer.data.LibraryKind
import com.xuanthanhtnt.xtplayer.metadata.TmdbClient
import com.xuanthanhtnt.xtplayer.ui.theme.LocalDimens
import com.xuanthanhtnt.xtplayer.ui.theme.LocalIsTv
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors

/** Deep matte black-green with a faint leaf glow — the backdrop for every screen. */
fun Modifier.appBackground(): Modifier = this
    .background(XtColors.background)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(XtColors.leaf.copy(alpha = 0.16f), Color.Transparent),
                center = Offset.Zero,
                radius = size.maxDimension * 0.75f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(XtColors.forest.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width, size.height),
                radius = size.maxDimension * 0.6f,
            )
        )
    }

/** Frosted panel used for cards and player chrome. */
fun Modifier.glassPanel(corner: Dp = 18.dp): Modifier = this
    .clip(RoundedCornerShape(corner))
    .background(XtColors.glass)
    .border(1.dp, XtColors.glassBorder, RoundedCornerShape(corner))

/**
 * Click + D-pad focus: on TV the focused tile grows slightly and gets a white outline (like the Apple TV
 * focus engine); on touch devices it is a plain ripple.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusClickable(
    shape: Shape = RoundedCornerShape(14.dp),
    focusScale: Float = 1.06f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val isTv = LocalIsTv.current
    val scale by animateFloatAsState(if (focused && isTv) focusScale else 1f, label = "focusScale")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
        .clip(shape)
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** Small capsule tag ("4K", "Dolby Vision", "TrueHD 7.1"). */
@Composable
fun TagChip(text: String, highlighted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        color = if (highlighted) Color.Black else Color.White.copy(alpha = 0.9f),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (highlighted) XtColors.brandGradient else Brush.linearGradient(listOf(XtColors.glass, XtColors.glass)))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A TMDb image, a local video frame, or a gradient placeholder with an icon. */
@Composable
fun ArtworkImage(
    path: String?,
    modifier: Modifier = Modifier,
    size: String = "w500",
    placeholderIcon: ImageVector = Icons.Filled.Movie,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val model = TmdbClient.imageModel(path, size)
    Box(modifier) {
        if (model == null) {
            Placeholder(placeholderIcon)
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { Placeholder(placeholderIcon) },
                error = { Placeholder(placeholderIcon) },
            )
        }
    }
}

@Composable
private fun BoxScope.Placeholder(icon: ImageVector) {
    Box(
        Modifier
            .matchParentSize()
            .background(Brush.linearGradient(listOf(XtColors.forest.copy(alpha = 0.55f), XtColors.surfaceHigh))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(36.dp))
    }
}

/** Thin progress bar along the bottom of a card. */
@Composable
fun ProgressLine(progress: Double, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.toFloat().coerceIn(0f, 1f))
                .height(4.dp)
                .background(XtColors.brandGradient)
        )
    }
}

/** 2:3 poster with title, progress and a watched check. */
@Composable
fun PosterCard(item: LibraryItem, progress: Double?, watched: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val dimens = LocalDimens.current
    Column(modifier.width(dimens.posterWidth)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .focusClickable(RoundedCornerShape(dimens.corner * 0.7f), onLongClick = onLongClick, onClick = onClick)
        ) {
            ArtworkImage(
                item.posterPath,
                Modifier.fillMaxSize(),
                size = "w342",
                placeholderIcon = if (item.kind == LibraryKind.SHOW) Icons.Filled.Tv else Icons.Filled.Movie,
            )
            if (watched) {
                Icon(
                    Icons.Filled.CheckCircle, "Đã xem",
                    tint = XtColors.leaf,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                )
            }
            if (progress != null && progress > 0) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.year?.let {
            Text("$it", style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 1)
        }
    }
}

/** 16:9 card for Continue Watching, episodes and the NAS browser. */
@Composable
fun LandscapeCard(
    title: String,
    subtitle: String?,
    imagePath: String?,
    progress: Double?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Filled.Movie,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val dimens = LocalDimens.current
    Column(modifier.width(dimens.landscapeWidth)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusClickable(RoundedCornerShape(dimens.corner * 0.7f), focusScale = 1.05f, onLongClick = onLongClick, onClick = onClick)
        ) {
            ArtworkImage(imagePath, Modifier.fillMaxSize(), size = "w780", placeholderIcon = placeholderIcon)
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            )
            if (progress != null && progress > 0) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(10.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A titled horizontal shelf, with "Xem tất cả" when there is a full grid behind it. */
@Composable
fun ShelfRow(title: String, onSeeAll: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    val dimens = LocalDimens.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.pagePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onSeeAll != null) {
                Text(
                    "Xem tất cả",
                    style = MaterialTheme.typography.labelLarge,
                    color = XtColors.leaf,
                    modifier = Modifier
                        .focusClickable(RoundedCornerShape(8.dp), onClick = onSeeAll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = dimens.pagePadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
            content = content,
        )
    }
}

/** Gradient square with an icon, title and detail — servers and quick actions on the Sources page. */
@Composable
fun SourceCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .focusClickable(RoundedCornerShape(18.dp), focusScale = 1.03f, onLongClick = onLongClick, onClick = onClick)
            .glassPanel()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(XtColors.brandGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = XtColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Large page heading (Infuse style). */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier, inset: Boolean = true) {
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(horizontal = if (inset) LocalDimens.current.pagePadding else 0.dp, vertical = 12.dp),
    )
}

@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier, inset: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = XtColors.secondaryText,
        modifier = modifier.padding(horizontal = if (inset) LocalDimens.current.pagePadding else 0.dp, vertical = 20.dp),
    )
}

val PagePadding: PaddingValues
    @Composable get() = PaddingValues(horizontal = LocalDimens.current.pagePadding)
