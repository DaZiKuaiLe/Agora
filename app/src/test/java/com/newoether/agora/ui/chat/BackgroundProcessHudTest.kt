package com.newoether.agora.ui.chat

import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.BackgroundProcessView
import com.newoether.agora.tool.ProcessTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundProcessHudTest {
    @Test
    fun detailMessageLooksLikeAFinishedStartCard() {
        val message = backgroundProcessDetailMessage(
            BackgroundProcessView(
                id = "ssh:box:abc",
                command = "ffmpeg -i in.mp4 out.mp4",
                server = "box",
            ),
        )
        val segment = message.segments!!.single()
        assertEquals(ProcessTools.START_REMOTE, segment.toolName)
        assertEquals(ToolExecutionStates.SUCCEEDED, segment.toolState)
        assertTrue(segment.toolArgs!!.contains("ffmpeg"))
        assertTrue(segment.toolResult!!.contains("process_id"))
        assertFalse(segment.toolState == ToolExecutionStates.RUNNING)
    }
}
