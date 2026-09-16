package com.aivideostudio.ui.screens.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aivideostudio.core.common.SizeUtils
import com.aivideostudio.domain.model.StorageUsage
import com.aivideostudio.domain.repository.StorageRepository
import com.aivideostudio.ui.components.SectionHeader
import com.aivideostudio.ui.components.StatRow
import com.aivideostudio.ui.components.StudioCard
import com.aivideostudio.ui.theme.StudioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val usage: StorageUsage = StorageUsage(),
    val freeBytes: Long = 0L,
    val message: String? = null,
    val pendingDeleteGenerated: Boolean = false,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val storageRepository: StorageRepository,
) : ViewModel() {

    private val freeBytes = MutableStateFlow(0L)
    private val message = MutableStateFlow<String?>(null)
    private val pendingDeleteGenerated = MutableStateFlow(false)

    val state: StateFlow<StorageUiState> = combine(
        storageRepository.observeUsage(),
        freeBytes,
        message,
        pendingDeleteGenerated,
    ) { usage, free, latestMessage, pending ->
        StorageUiState(
            usage = usage,
            freeBytes = free,
            message = latestMessage,
            pendingDeleteGenerated = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            storageRepository.refresh()
            freeBytes.value = storageRepository.availableBytes()
        }
    }

    fun clearCache() = runAction { storageRepository.clearCache() }

    fun deleteTemporary() = runAction { storageRepository.deleteTemporaryFiles() }

    fun requestDeleteGenerated() {
        pendingDeleteGenerated.value = true
    }

    fun cancelDeleteGenerated() {
        pendingDeleteGenerated.value = false
    }

    fun deleteGenerated() {
        pendingDeleteGenerated.value = false
        runAction { storageRepository.deleteGeneratedFiles() }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun runAction(block: suspend () -> Long) {
        viewModelScope.launch {
            val freed = block()
            freeBytes.value = storageRepository.availableBytes()
            message.value = "Freed ${SizeUtils.formatBytes(freed)}"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = StudioColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = StudioColors.Background,
                    titleContentColor = StudioColors.TextPrimary,
                    navigationIconContentColor = StudioColors.TextPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val usage = state.usage
        val grandTotal = (usage.totalBytes + state.freeBytes).coerceAtLeast(1L)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionHeader(
                title = "Device storage",
                subtitle = "AI Video Studio is using ${SizeUtils.formatBytes(usage.totalBytes)}",
            )

            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SegmentedUsageBar(
                        segments = listOf(
                            StudioColors.Primary to usage.originalMediaBytes,
                            StudioColors.Secondary to usage.generatedClipBytes,
                            StudioColors.Warning to usage.cacheBytes,
                            StudioColors.Tertiary to usage.tempBytes,
                        ),
                        total = usage.totalBytes,
                    )
                    UsageRow(
                        label = "Original media",
                        bytes = usage.originalMediaBytes,
                        fraction = usage.originalMediaBytes.toFloat() / grandTotal,
                        color = StudioColors.Primary,
                    )
                    UsageRow(
                        label = "Generated clips",
                        bytes = usage.generatedClipBytes,
                        fraction = usage.generatedClipBytes.toFloat() / grandTotal,
                        color = StudioColors.Secondary,
                    )
                    UsageRow(
                        label = "Cache",
                        bytes = usage.cacheBytes,
                        fraction = usage.cacheBytes.toFloat() / grandTotal,
                        color = StudioColors.Warning,
                    )
                    UsageRow(
                        label = "Temporary files",
                        bytes = usage.tempBytes,
                        fraction = usage.tempBytes.toFloat() / grandTotal,
                        color = StudioColors.Tertiary,
                    )
                    UsageRow(
                        label = "Free space",
                        bytes = state.freeBytes,
                        fraction = state.freeBytes.toFloat() / grandTotal,
                        color = StudioColors.Outline,
                    )
                }
            }

            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = StudioColors.Success,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Original videos are never deleted by AI Video Studio.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.clearCache() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear cache")
                }
                OutlinedButton(
                    onClick = { viewModel.requestDeleteGenerated() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete generated files")
                }
                OutlinedButton(
                    onClick = { viewModel.deleteTemporary() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete temporary files")
                }
            }
        }
    }

    if (state.pendingDeleteGenerated) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteGenerated() },
            title = { Text("Delete generated files?") },
            text = {
                Text(
                    "This removes every rendered clip and export. " +
                        "Original videos are never deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteGenerated() }) {
                    Text("Delete", color = StudioColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDeleteGenerated() }) { Text("Cancel") }
            },
            containerColor = StudioColors.SurfaceElevated,
            titleContentColor = StudioColors.TextPrimary,
            textContentColor = StudioColors.TextSecondary,
        )
    }
}

@Composable
private fun UsageRow(
    label: String,
    bytes: Long,
    fraction: Float,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatRow(label = label, value = SizeUtils.formatBytes(bytes))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(StudioColors.SurfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
        }
    }
}

/** Proportional bar where each slice keeps its share of the total. */
@Composable
private fun SegmentedUsageBar(
    segments: List<Pair<Color, Long>>,
    total: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(StudioColors.SurfaceVariant),
    ) {
        if (total > 0L) {
            segments.forEach { (color, value) ->
                if (value > 0L) {
                    Box(
                        modifier = Modifier
                            .weight(value.toFloat())
                            .fillMaxHeight()
                            .background(color),
                    )
                }
            }
        }
    }
}
