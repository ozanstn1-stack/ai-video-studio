package com.aivideostudio.ui.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aivideostudio.ui.theme.StudioColors
import java.io.File

/**
 * Coil renders video files through the `VideoFrameDecoder` registered on the
 * application's image loader, so a thumbnail is requested exactly like an image.
 */
@Composable
fun VideoThumbnail(
    source: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    aspectRatio: Float = 16f / 9f,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(StudioColors.SurfaceVariant)
            .aspectRatio(aspectRatio),
        contentAlignment = Alignment.Center,
    ) {
        if (source != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(if (source.startsWith("/")) File(source) else source)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "No preview",
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextTertiary,
            )
        }
    }
}

/**
 * Media3 player surface. The player instance is owned by the caller's
 * ViewModel-free composition and released when it leaves the tree, which keeps
 * playback from leaking across navigation.
 */
@OptIn(UnstableApi::class)
@Composable
fun StudioVideoPlayer(
    mediaUri: String?,
    modifier: Modifier = Modifier,
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = false,
    loop: Boolean = false,
    showControls: Boolean = true,
    onPlayerReady: (ExoPlayer) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember(mediaUri) {
        mediaUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = if (loop) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE
                } else {
                    androidx.media3.common.Player.REPEAT_MODE_OFF
                }
                seekTo(startPositionMs)
                prepare()
                this.playWhenReady = playWhenReady
            }
        }
    }

    DisposableEffect(player) {
        player?.let(onPlayerReady)
        onDispose { player?.release() }
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (player == null) {
            Text(
                text = "Video unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = StudioColors.TextTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = showControls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view -> view.player = player },
        )
    }
}

/** Compact duration pill overlaid on a thumbnail. */
@Composable
fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Full-width gradient scrim used behind text drawn over media. */
@Composable
fun BottomScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                ),
            ),
    )
}
