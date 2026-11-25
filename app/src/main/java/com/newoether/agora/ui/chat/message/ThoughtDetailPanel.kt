package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import kotlinx.coroutines.delay

@Composable
internal fun ThoughtDetailLazyPanel(
    content: String,
    isStreaming: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    emptyStreamingText: String? = null,
) {
    val published = rememberThrottledThoughtDetail(content, isStreaming)
    val chunks = remember(published) { thoughtDetailChunks(published) }
    val style = ChatType.thoughtBody
    val color = MaterialTheme.colorScheme.onSurface
    val showEmpty = isStreaming && published.isBlank() && emptyStreamingText != null
    NoAutoScrollSelectionContainer(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 32.dp),
        ) {
            if (showEmpty) {
                item(key = "empty-stream") {
                    Text(
                        text = emptyStreamingText.orEmpty(),
                        style = style,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                itemsIndexed(
                    items = chunks,
                    key = { index, _ -> index },
                ) { _, chunk ->
                    if (isStreaming) {
                        Text(
                            text = chunk,
                            modifier = Modifier.fillMaxWidth(),
                            style = style,
                            color = color,
                        )
                    } else {
                        SearchHighlightedPlainText(
                            text = chunk,
                            style = style,
                            color = color,
                            spec = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThoughtDetailEmbeddedChunks(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    emptyStreamingText: String? = null,
) {
    val published = rememberThrottledThoughtDetail(content, isStreaming)
    val chunks = remember(published) { thoughtDetailChunks(published) }
    val style = ChatType.thoughtBody
    val color = MaterialTheme.colorScheme.onSurface
    val showEmpty = isStreaming && published.isBlank() && emptyStreamingText != null
    Column(modifier = modifier.fillMaxWidth()) {
        if (showEmpty) {
            Text(
                text = emptyStreamingText.orEmpty(),
                style = style,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            chunks.forEach { chunk ->
                if (isStreaming) {
                    Text(
                        text = chunk,
                        modifier = Modifier.fillMaxWidth(),
                        style = style,
                        color = color,
                    )
                } else {
                    SearchHighlightedPlainText(
                        text = chunk,
                        style = style,
                        color = color,
                        spec = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberThrottledThoughtDetail(content: String, streaming: Boolean): String {
    var published by remember { mutableStateOf(content) }
    val incoming = rememberUpdatedState(content)
    LaunchedEffect(streaming) {
        published = incoming.value
        if (!streaming) return@LaunchedEffect
        while (true) {
            delay(THOUGHT_DETAIL_THROTTLE_MS.toLong())
            val next = incoming.value
            if (next != published) published = next
        }
    }
    LaunchedEffect(streaming, content) {
        if (!streaming) published = content
    }
    return published
}
