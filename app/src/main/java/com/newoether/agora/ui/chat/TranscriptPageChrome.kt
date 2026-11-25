package com.newoether.agora.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

internal suspend fun flashListToEnd(listState: LazyListState) {
    repeat(4) {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) return
        if (!listState.canScrollForward && listState.firstVisibleItemIndex >= lastIndex) return
        listState.scrollToItem(lastIndex)
        if (!listState.canScrollForward) return
        listState.scroll { scrollBy(1_000_000f) }
        if (!listState.canScrollForward) return
    }
}

@Composable
internal fun BoxScope.TranscriptPageChrome(
    listState: LazyListState,
    hasEarlierPage: Boolean,
    earlierPageGeneration: Int,
    onPreviousPage: () -> Unit,
) {
    val latestHasEarlier by rememberUpdatedState(hasEarlierPage)
    val latestPrevious by rememberUpdatedState(onPreviousPage)
    var paging by remember { mutableStateOf(false) }
    LaunchedEffect(earlierPageGeneration) {
        if (earlierPageGeneration == 0) return@LaunchedEffect
        paging = true
        try {
            withFrameNanos { }
            flashListToEnd(listState)
            withFrameNanos { }
            flashListToEnd(listState)
        } finally {
            paging = false
        }
    }
    LaunchedEffect(listState) {
        var dragged = false
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> dragged = true
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> {
                    val atTop = dragged &&
                        latestHasEarlier &&
                        !paging &&
                        !listState.canScrollBackward &&
                        listState.canScrollForward
                    dragged = false
                    if (atTop) latestPrevious()
                }
            }
        }
    }
    val pageFits = !listState.canScrollBackward && !listState.canScrollForward
    if (hasEarlierPage && pageFits) {
        TextButton(
            onClick = onPreviousPage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        ) {
            Text(stringResource(R.string.transcript_previous_page))
        }
    }
}
