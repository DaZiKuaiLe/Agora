package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.viewmodel.CompactBatchProgress
import com.newoether.agora.viewmodel.CompactProgressKind
import kotlinx.coroutines.flow.StateFlow

/** Blocks chat interaction while summary-batch compact is in progress. */
@Composable
internal fun ContextCompactBlockingOverlay(
    visible: Boolean,
    batchProgressFlow: StateFlow<CompactBatchProgress?>,
) {
    val batchProgress by batchProgressFlow.collectAsState()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(enabled = true, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MotionAwareCircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.inversePrimary,
                        strokeWidth = 5.dp,
                    )
                    Box(modifier = Modifier.height(16.dp))
                    val progressValue = batchProgress
                    val isOptimize = progressValue?.kind == CompactProgressKind.OPTIMIZE
                    Text(
                        text = if (progressValue != null && progressValue.total > 0) {
                            stringResource(
                                if (isOptimize) {
                                    R.string.context_optimizing_progress
                                } else {
                                    R.string.context_compacting_progress
                                },
                                progressValue.completed,
                                progressValue.total,
                            )
                        } else {
                            stringResource(
                                if (isOptimize) R.string.context_optimizing
                                else R.string.context_compacting,
                            )
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }
    }
}
