package com.newoether.agora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FadeVerticalScrollbarTest {
    @Test
    fun thumbFractionIsZeroWhenTheTrackCannotTravel() {
        assertEquals(0f, thumbFraction(12f, 0f))
        assertEquals(0f, thumbFraction(0f, -4f))
    }

    @Test
    fun thumbFractionClampsToTheTrack() {
        assertEquals(0f, thumbFraction(-8f, 100f))
        assertEquals(0.25f, thumbFraction(25f, 100f))
        assertEquals(1f, thumbFraction(140f, 100f))
    }

    @Test
    fun trackDeltaMapsThroughMaxScrollOverThumbTravel() {
        assertEquals(0f, scrollbarTrackToContentDelta(10f, 800f, 0f))
        assertEquals(80f, scrollbarTrackToContentDelta(10f, 800f, 100f), 0.01f)
        assertEquals(-40f, scrollbarTrackToContentDelta(-5f, 800f, 100f), 0.01f)
    }

    @Test
    fun trackDeltaMatchesContentOverTrackWhenThumbIsProportional() {
        val track = 500f
        val viewport = 800f
        val content = 4000f
        val maxScroll = content - viewport
        val thumb = track * (viewport / content)
        val maxThumbTravel = track - thumb
        val trackDelta = 20f
        assertEquals(
            trackDelta * (maxScroll / maxThumbTravel),
            scrollbarTrackToContentDelta(trackDelta, maxScroll, maxThumbTravel),
            0.01f,
        )
    }
}
