package com.newoether.agora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private val ThumbWidth = 3.dp
private val ThumbInset = 2.dp
private val TouchWidth = 24.dp
private val MinThumbHeight = 24.dp
private const val FadeInMs = 150
private const val FadeOutMs = 400
private const val FadeOutDelayMs = 1_000L

internal data class LazyScrollbarMetrics(
    val thumbOffsetPx: Float,
    val thumbHeightPx: Float,
    val trackHeightPx: Float,
    val viewportHeightPx: Float,
    val maxScrollPx: Float,
    val maxThumbTravelPx: Float,
)

/**
 * Android-style fade scrollbar for a [LazyListState], drawn as an overlay sibling of the list.
 * Visible while scrolling or dragging; consumes pointer events only while interactive so it does
 * not steal list gestures when hidden.
 *
 * Pass top/bottom insets via [trackPadding] to keep the track inside the unobscured list
 * band (for example the space between the chat top bar and the composer).
 *
 * All geometry is in layout pixels (Compose px). Dp sizes are converted with [LocalDensity]
 * so high-DPI devices stay aligned with the list's own pixel space.
 */
@Composable
fun BoxScope.FadeVerticalLazyListScrollbar(
    state: LazyListState,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
    modifier: Modifier = Modifier,
    trackPadding: PaddingValues = PaddingValues(),
) {
    val density = LocalDensity.current
    val minThumbPx = with(density) { MinThumbHeight.toPx() }
    // Settle tolerance in layout px (~1dp) so end-of-drag does not flash on high density.
    val settleEpsilonPx = with(density) { 1.dp.toPx() }
    val alpha = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragThumbOffset by remember { mutableFloatStateOf(Float.NaN) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val latestState by rememberUpdatedState(state)

    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to dragging }
            .collectLatest { (scrolling, isDragging) ->
                if (scrolling || isDragging) {
                    alpha.animateTo(1f, tween(FadeInMs))
                } else {
                    delay(FadeOutDelayMs)
                    alpha.animateTo(0f, tween(FadeOutMs))
                }
            }
    }

    val layoutInfo = state.layoutInfo
    val firstIndex = state.firstVisibleItemIndex
    val firstOffset = state.firstVisibleItemScrollOffset
    val canScroll = state.canScrollForward || state.canScrollBackward
    val alphaValue = alpha.value

    val metrics = remember(
        layoutInfo,
        firstIndex,
        firstOffset,
        canScroll,
        minThumbPx,
        trackHeightPx,
    ) {
        computeLazyScrollbarMetrics(state, minThumbPx, trackHeightPx)
    }
    val latestMetrics by rememberUpdatedState(metrics)
    val interactive = alphaValue > 0.05f && canScroll && metrics != null

    // After a drag ends, LazyList position is estimated from average item heights and can
    // disagree with the finger thumb for a frame or two. Hold the drag thumb until metrics
    // settle within 1dp to avoid a visible snap/flash (worse on high-DPI where px error is large).
    LaunchedEffect(metrics?.thumbOffsetPx, dragging, settleEpsilonPx) {
        if (dragging) return@LaunchedEffect
        val held = dragThumbOffset
        if (held.isNaN()) return@LaunchedEffect
        val target = metrics?.thumbOffsetPx ?: run {
            dragThumbOffset = Float.NaN
            return@LaunchedEffect
        }
        if (abs(held - target) <= settleEpsilonPx) {
            dragThumbOffset = Float.NaN
        } else {
            // Estimation diverged; ease to metrics without an instant jump.
            val anim = Animatable(held)
            anim.animateTo(target, tween(120)) { dragThumbOffset = value }
            dragThumbOffset = Float.NaN
        }
    }

    if (!canScroll) return

    val displayThumbOffset = when {
        metrics == null -> 0f
        !dragThumbOffset.isNaN() -> dragThumbOffset
        else -> metrics.thumbOffsetPx
    }
    val thumbHeightPx = metrics?.thumbHeightPx ?: 0f

    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(
                top = trackPadding.calculateTopPadding(),
                bottom = trackPadding.calculateBottomPadding(),
            )
            .zIndex(1f)
            // Collapse when hidden so an invisible strip cannot steal list gestures.
            .width(if (alphaValue > 0.01f || dragging) TouchWidth else 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                .graphicsLayer { this.alpha = alphaValue }
                .drawWithContent {
                    drawContent()
                    if (alphaValue <= 0.01f || thumbHeightPx <= 0f) return@drawWithContent
                    val barWidth = ThumbWidth.toPx()
                    val inset = ThumbInset.toPx()
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(size.width - barWidth - inset, displayThumbOffset),
                        size = Size(barWidth, thumbHeightPx),
                        cornerRadius = CornerRadius(barWidth / 2f),
                    )
                }
                .then(
                    if (interactive) {
                        // Keys must stay stable. Re-keying on thumb metrics cancels the active
                        // drag as soon as scroll updates layout.
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val startMetrics = latestMetrics ?: return@awaitEachGesture
                                val travel = startMetrics.maxThumbTravelPx.coerceAtLeast(0f)
                                if (travel <= 0f || startMetrics.thumbHeightPx <= 0f) {
                                    return@awaitEachGesture
                                }
                                down.consume()
                                dragging = true
                                val jumpThumb =
                                    (down.position.y - startMetrics.thumbHeightPx / 2f)
                                        .coerceIn(0f, travel)
                                latestState.dispatchRawDelta(
                                    scrollbarTrackToContentDelta(
                                        trackDeltaPx = jumpThumb - startMetrics.thumbOffsetPx,
                                        maxScrollPx = startMetrics.maxScrollPx,
                                        maxThumbTravelPx = startMetrics.maxThumbTravelPx,
                                    ),
                                )
                                dragThumbOffset = jumpThumb
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val drag = event.changes.firstOrNull { it.id == down.id }
                                    if (drag == null || !drag.pressed) break
                                    val dy = drag.positionChange().y
                                    drag.consume()
                                    val current = latestMetrics ?: continue
                                    val currentTravel = current.maxThumbTravelPx.coerceAtLeast(0f)
                                    val requested = scrollbarTrackToContentDelta(
                                        trackDeltaPx = dy,
                                        maxScrollPx = current.maxScrollPx,
                                        maxThumbTravelPx = current.maxThumbTravelPx,
                                    )
                                    val consumed = latestState.dispatchRawDelta(requested)
                                    val trackConsumed =
                                        if (requested == 0f) 0f else dy * (consumed / requested)
                                    val base =
                                        if (dragThumbOffset.isNaN()) {
                                            jumpThumb
                                        } else {
                                            dragThumbOffset
                                        }
                                    dragThumbOffset =
                                        (base + trackConsumed).coerceIn(0f, currentTravel)
                                }
                                dragging = false
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

internal fun thumbFraction(thumbOffset: Float, maxThumbTravel: Float): Float =
    if (maxThumbTravel <= 0f) 0f else (thumbOffset / maxThumbTravel).coerceIn(0f, 1f)

/**
 * Maps a track-pixel drag to a content-pixel scroll.
 * [maxScrollPx] / [maxThumbTravelPx] is the correct ratio; both are in layout pixels
 * (Compose px), so density/DPR must not be applied a second time.
 */
internal fun scrollbarTrackToContentDelta(
    trackDeltaPx: Float,
    maxScrollPx: Float,
    maxThumbTravelPx: Float,
): Float {
    if (maxThumbTravelPx <= 0f) return 0f
    return trackDeltaPx * (maxScrollPx / maxThumbTravelPx)
}

internal fun computeLazyScrollbarMetrics(
    state: LazyListState,
    minThumbPx: Float,
    trackHeightPx: Float,
): LazyScrollbarMetrics? {
    if (trackHeightPx <= 0f) return null
    val layoutInfo = state.layoutInfo
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null

    val viewportHeight =
        (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    if (viewportHeight <= 0f) return null

    val averageItemSize = visible.sumOf { it.size }.toFloat() / visible.size
    if (averageItemSize <= 0f) return null

    val estimatedContentHeight =
        averageItemSize * layoutInfo.totalItemsCount +
            layoutInfo.beforeContentPadding +
            layoutInfo.afterContentPadding
    if (estimatedContentHeight <= viewportHeight &&
        !state.canScrollForward &&
        !state.canScrollBackward
    ) {
        return null
    }

    val contentHeight = estimatedContentHeight.coerceAtLeast(viewportHeight + 1f)
    val thumbHeightPx = (trackHeightPx * (viewportHeight / contentHeight))
        .coerceAtLeast(minThumbPx)
        .coerceAtMost(trackHeightPx)
    // Use LazyListState scroll APIs (layout px). Mixing item.offset with beforeContentPadding
    // double-counted padding on some densities and made the thumb drift after scroll.
    val scrolledPixels =
        state.firstVisibleItemIndex * averageItemSize +
            state.firstVisibleItemScrollOffset.toFloat()
    val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(1f)
    val maxThumbTravel = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbOffset =
        (scrolledPixels / maxScroll * maxThumbTravel).coerceIn(0f, maxThumbTravel)

    return LazyScrollbarMetrics(
        thumbOffsetPx = thumbOffset,
        thumbHeightPx = thumbHeightPx,
        trackHeightPx = trackHeightPx,
        viewportHeightPx = viewportHeight,
        maxScrollPx = maxScroll,
        maxThumbTravelPx = maxThumbTravel,
    )
}
