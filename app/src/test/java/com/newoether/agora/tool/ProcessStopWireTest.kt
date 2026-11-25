package com.newoether.agora.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessStopWireTest {
    @Test
    fun stopResultTellsTheModelWhetherTheProcessDied() {
        data class Case(val name: String, val stillRunning: Boolean)
        listOf(
            Case("exited", stillRunning = false),
            Case("still running", stillRunning = true),
        ).forEach { case ->
            val wire = ProcessTools.stopWire(case.stillRunning)
            val raw = formatExecResult(
                type = ProcessTools.STOP,
                command = "sleep 30",
                server = "Local",
                exitCode = if (wire.running) -1 else 137,
                stdout = "",
                stderr = "",
                okExit = null,
                detailStore = null,
                processId = "local:aa",
                running = wire.running,
                stopped = wire.stopped,
                ok = wire.ok,
                error = wire.error,
            )
            val obj = Json.parseToJsonElement(raw).jsonObject
            assertEquals(case.name, wire.running, obj.getValue("running").jsonPrimitive.boolean)
            assertEquals(case.name, wire.ok, obj.getValue("ok").jsonPrimitive.boolean)
            assertEquals(case.name, "local:aa", obj.getValue(ProcessTools.PROCESS_ID).jsonPrimitive.content)
            if (wire.stopped) {
                assertEquals(case.name, true, obj.getValue("stopped").jsonPrimitive.boolean)
                assertNull(case.name, obj["error"]?.jsonPrimitive?.contentOrNull)
            } else {
                assertNull(case.name, obj["stopped"])
                assertEquals(case.name, ProcessTools.STILL_RUNNING, obj.getValue("error").jsonPrimitive.content)
            }
        }
    }
}
