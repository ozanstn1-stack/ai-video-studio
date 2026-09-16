package com.aivideostudio.ui.screens.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aivideostudio.domain.repository.SettingsRepository
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Three sentences and one button. Permissions are requested later, in context,
 * the first time a feature actually needs them.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(700),
        label = "onboardingAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            StudioColors.Primary.copy(alpha = 0.28f),
                            StudioColors.Background,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "AI Video Studio",
                style = MaterialTheme.typography.displayMedium,
                color = StudioColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Turn hours of camera footage into short-form videos.",
                style = MaterialTheme.typography.bodyLarge,
                color = StudioColors.TextSecondary,
            )

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier.alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OnboardingPoint(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Analyze",
                    message = "Scenes, sound and speech are measured on your device.",
                )
                OnboardingPoint(
                    icon = Icons.Outlined.ContentCut,
                    title = "Edit",
                    message = "The best moments are assembled into short stories.",
                )
                OnboardingPoint(
                    icon = Icons.Outlined.Subtitles,
                    title = "Caption",
                    message = "Subtitles, titles and descriptions are written for you.",
                )
                OnboardingPoint(
                    icon = Icons.Outlined.Upload,
                    title = "Export",
                    message = "Ready for TikTok, Reels and Shorts in the right shape.",
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = "Your original videos stay on your device unless you " +
                    "explicitly enable cloud AI processing.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
            )

            Button(
                onClick = {
                    viewModel.complete()
                    onFinished()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Primary,
                    contentColor = StudioColors.OnPrimary,
                ),
            ) {
                Text("Get Started", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun OnboardingPoint(
    icon: ImageVector,
    title: String,
    message: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(StudioColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = StudioColors.PrimaryBright)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = StudioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextTertiary,
            )
        }
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    fun complete() {
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
    }
}
