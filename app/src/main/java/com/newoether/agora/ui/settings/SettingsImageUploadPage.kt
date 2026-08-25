package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

/** Discrete long-edge (px) ceiling choices for uploaded images, in ascending order. */
private val UPLOAD_DIMENSIONS = listOf(64, 128, 256, 512, 1024, 2048)

/** Short labels shown under the slider for each position. */
private val UPLOAD_DIMENSION_LABELS = listOf("64", "128", "256", "512", "1K", "2K")

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsImageUploadPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val maxDimension by viewModel.settings.imageUploadMaxDimension.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    // Slider positions are 0..(size-1); snap the persisted value to the nearest index.
    val index = remember(maxDimension) { UPLOAD_DIMENSIONS.indexOf(maxDimension).coerceAtLeast(0) }
    var sliderPosition by remember { mutableFloatStateOf(index.toFloat()) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_image_upload),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("image-upload.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.settings_image_upload), items = buildList {
                add {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.image_upload_max_dimension),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.image_upload_max_dimension_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    "${maxDimension}px",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                Slider(
                                    value = sliderPosition,
                                    onValueChange = { sliderPosition = it },
                                    onValueChangeFinished = {
                                        val committed = UPLOAD_DIMENSIONS[
                                            sliderPosition.toInt().coerceIn(0, UPLOAD_DIMENSIONS.size - 1)
                                        ]
                                        if (committed != maxDimension) {
                                            viewModel.settings.setImageUploadMaxDimension(committed)
                                        }
                                    },
                                    valueRange = 0f..(UPLOAD_DIMENSIONS.size - 1).toFloat(),
                                    steps = UPLOAD_DIMENSIONS.size - 2,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    UPLOAD_DIMENSION_LABELS.forEach { label ->
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            })
        }
    }
}
