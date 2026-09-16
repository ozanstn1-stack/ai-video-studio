package com.aivideostudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aivideostudio.domain.model.ClipStatus
import com.aivideostudio.domain.model.GeneratedClip
import com.aivideostudio.ui.theme.StudioColors

/**
 * One generated Short, complete with the metadata the user needs to decide what
 * to do with it. Used by the library grid and the per-project clip list.
 */
@Composable
fun ClipCard(
    clip: GeneratedClip,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    showActions: Boolean = false,
) {
    val aspect = clip.aspectRatio.aspectValue

    StudioCard(modifier = modifier, onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect.coerceIn(0.5f, 2f))
                    .clip(RoundedCornerShape(14.dp))
                    .background(StudioColors.SurfaceVariant),
            ) {
                VideoThumbnail(
                    source = clip.thumbnailPath ?: clip.outputPath,
                    modifier = Modifier.fillMaxWidth(),
                    aspectRatio = aspect.coerceIn(0.5f, 2f),
                )
                StudioBadge(
                    text = "${clip.label.emoji} ${clip.label.displayName}",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    color = StudioColors.PrimaryBright,
                )
                DurationBadge(
                    text = clip.durationLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
                if (clip.hookText != null) {
                    Text(
                        text = clip.hookText,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .fillMaxWidth(0.7f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = clip.title.ifBlank { "Untitled Short ${clip.index + 1}" },
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Row {
                    Text(
                        text = clip.durationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextTertiary,
                    )
                    Text(
                        text = "  \u00B7  ${clip.aspectRatio.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextTertiary,
                    )
                }
                if (clip.description.isNotBlank()) {
                    Text(
                        text = clip.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        maxLines = 2,
                    )
                }
                if (clip.hashtags.isNotEmpty()) {
                    Text(
                        text = clip.hashtags.take(5).joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.Secondary,
                        maxLines = 1,
                    )
                }
            }

            if (showActions) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    onEdit?.let {
                        ClipAction(Icons.Outlined.Edit, "Edit", it, Modifier.weight(1f))
                    }
                    onExport?.let {
                        ClipAction(Icons.Outlined.Upload, "Export", it, Modifier.weight(1f))
                    }
                    onShare?.let {
                        ClipAction(Icons.Outlined.IosShare, "Share", it, Modifier.weight(1f))
                    }
                    onDelete?.let {
                        ClipAction(
                            Icons.Outlined.Delete,
                            "Delete",
                            it,
                            Modifier.weight(1f),
                            danger = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val tint = if (danger) StudioColors.Error else StudioColors.TextSecondary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(StudioColors.SurfaceVariant)
            .androidClickable(onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
fun ClipStatusBadge(status: ClipStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        ClipStatus.DRAFT -> "Draft" to StudioColors.TextTertiary
        ClipStatus.RENDERING -> "Rendering" to StudioColors.Warning
        ClipStatus.READY -> "Ready" to StudioColors.Secondary
        ClipStatus.EXPORTED -> "Exported" to StudioColors.Success
        ClipStatus.FAILED -> "Failed" to StudioColors.Error
    }
    StudioBadge(text = text, modifier = modifier, color = color)
}

@Composable
fun TimelineStub(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(StudioColors.SurfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.4f)
                .height(6.dp)
                .background(StudioColors.Primary),
        )
        Spacer(Modifier.width(2.dp))
    }
}

/** Small helper so cards can render a "no content" hint consistently. */
@Composable
fun NoContentHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.ContentCut,
            contentDescription = null,
            tint = StudioColors.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextTertiary)
    }
}
