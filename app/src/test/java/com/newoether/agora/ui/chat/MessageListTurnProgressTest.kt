package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageListTurnProgressTest {
    @Test
    fun visibleTurnProgress_usesTopItemAndMapsSentinelToLastTurn() {
        data class Case(
            val name: String,
            val firstVisible: Int,
            val turnCount: Int,
            val current: Int?,
            val total: Int?,
        )
        val cases = listOf(
            Case("empty", 0, 0, null, null),
            Case("first turn", 0, 588, 1, 588),
            Case("mid turn", 35, 588, 36, 588),
            Case("last turn", 587, 588, 588, 588),
            Case("bottom sentinel", 588, 588, 588, 588),
            Case("negative index", -1, 10, 1, 10),
        )
        for (case in cases) {
            val progress = visibleMessageListTurnProgress(case.firstVisible, case.turnCount)
            assertEquals(case.name, case.current, progress?.current)
            assertEquals(case.name, case.total, progress?.total)
        }
    }
}
