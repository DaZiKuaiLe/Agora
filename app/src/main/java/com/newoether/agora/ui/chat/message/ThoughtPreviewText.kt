package com.newoether.agora.ui.chat.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.newoether.agora.ui.theme.ChatType

private const val STREAMING_THOUGHT_PREVIEW_CODE_POINTS = 60

internal fun thoughtPreviewTailSource(
    content: String,
    maximumCodePoints: Int = STREAMING_THOUGHT_PREVIEW_CODE_POINTS,
): String {
    if (content.isEmpty() || maximumCodePoints <= 0) return content
    val codePointCount = content.codePointCount(0, content.length)
    if (codePointCount <= maximumCodePoints) return content
    val start = content.offsetByCodePoints(0, codePointCount - maximumCodePoints)
    return content.substring(start)
}

internal fun thoughtPreviewTail(
    content: AnnotatedString,
    maximumCodePoints: Int = STREAMING_THOUGHT_PREVIEW_CODE_POINTS,
): AnnotatedString {
    if (content.isEmpty() || maximumCodePoints <= 0) return content
    val raw = content.text
    val codePointCount = raw.codePointCount(0, raw.length)
    if (codePointCount <= maximumCodePoints) return content
    val start = raw.offsetByCodePoints(0, codePointCount - maximumCodePoints)
    return AnnotatedString.Builder().apply {
        append("…")
        append(content.subSequence(start, content.length))
    }.toAnnotatedString()
}

@Composable
internal fun StreamingThoughtPreviewText(
    content: String,
    streaming: Boolean,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val flat = remember(content, streaming) {
        val source = if (streaming) {
            thoughtPreviewTailSource(content)
        } else {
            content
        }
        source.replace('\n', ' ')
    }
    val annotated = remember(flat) { AnnotatedString(flat) }
    val preview = remember(annotated, streaming) {
        if (streaming) thoughtPreviewTail(annotated) else annotated
    }
    StableStreamingText(
        text = preview.text,
        streaming = streaming,
        style = ChatType.metaNormal,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
