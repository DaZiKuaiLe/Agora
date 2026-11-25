package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext

internal object AsyncCommandToolDefinitions {
    val NAMES = ProcessTools.NAMES

    fun build(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled) return emptyList()
        val hasLocal = ctx.localCommandEnabled
        val remoteNames = ctx.shellDevices
            .filter { it.type == "ssh" }
            .map { it.name.ifBlank { "Untitled" } }
        if (!hasLocal && remoteNames.isEmpty()) return emptyList()

        val serverPropDesc = when {
            remoteNames.isEmpty() -> "SSH remote terminal name."
            remoteNames.size == 1 ->
                "The SSH remote terminal name. Omit to use the only available one: ${remoteNames[0]}."
            else ->
                "The SSH remote terminal name. Use list_remote_shells first. Available: ${remoteNames.joinToString(", ")}."
        }
        val remoteRequired =
            if (remoteNames.size == 1) listOf("command")
            else listOf("command", "server")
        val tools = mutableListOf<ToolDefinition>()
        val waitProp = ToolProperty(
            "integer",
            "Optional initial wait in milliseconds (default 0 = return immediately with " +
                "{running, ${ProcessTools.PROCESS_ID}} if still running; hard ceiling 295000). " +
                "Required on ${ProcessTools.WAIT}.",
        )
        if (remoteNames.isNotEmpty()) {
            tools += tool(
                ProcessTools.START_REMOTE,
                "Start a process on an SSH host. wait_ms defaults to 0 so you can report or do " +
                    "other work immediately. If it finishes in the wait, returns exit/stdout/stderr. " +
                    "Otherwise the process keeps running and this returns {running, ${ProcessTools.PROCESS_ID}} " +
                    "plus any partial output. That is background: ${ProcessTools.BACKGROUND_EXIT_NOTIFY}. " +
                    "Do not poll. Use ${ProcessTools.LIST} / ${ProcessTools.GET} / ${ProcessTools.WAIT} / " +
                    "${ProcessTools.STOP}. Required when the command may take ${ProcessTools.LONG_PROCESS_SECONDS}s " +
                    "or more, duration is unknown, or you need to speak first. Do not sleep. " +
                    "A timed-out execute_remote_command is not promoted into this. Match command_syntax from list_remote_shells.",
                mapOf(
                    "command" to ToolProperty("string", "The command to execute."),
                    "server" to ToolProperty("string", serverPropDesc),
                    "wait_ms" to waitProp,
                    "workdir" to ToolProperty("string", "Working directory (optional)."),
                    "ok_exit" to ToolProperty(
                        "array",
                        "Optional. If the exit code is in this set, return code and byte lengths only; the body is stored for query_detail_store.",
                        items = ToolProperty("integer", "Accepted exit code."),
                    ),
                ),
                remoteRequired,
            )
        }
        if (hasLocal) {
            tools += tool(
                ProcessTools.START_LOCAL,
                "Start a process on this device. wait_ms defaults to 0 so you can report or do " +
                    "other work immediately. If it finishes, returns exit/stdout/stderr. Otherwise " +
                    "returns {running, ${ProcessTools.PROCESS_ID}} without killing the process. " +
                    "That is background: ${ProcessTools.BACKGROUND_EXIT_NOTIFY}. Do not poll. " +
                    "Use ${ProcessTools.LIST} / ${ProcessTools.GET} / ${ProcessTools.WAIT} / ${ProcessTools.STOP}. " +
                    "Required when the command may take ${ProcessTools.LONG_PROCESS_SECONDS}s or more, " +
                    "duration is unknown, or you need to speak first. Do not sleep. " +
                    "A timed-out execute_local_command is not promoted into this.",
                mapOf(
                    "command" to ToolProperty("string", "The local command to execute."),
                    "wait_ms" to waitProp,
                    "workdir" to ToolProperty("string", "Working directory (optional)."),
                    "ok_exit" to ToolProperty(
                        "array",
                        "Optional. If the exit code is in this set, return code and byte lengths only; the body is stored for query_detail_store.",
                        items = ToolProperty("integer", "Accepted exit code."),
                    ),
                ),
                listOf("command"),
            )
        }
        tools += tool(
            ProcessTools.GET,
            "Peek at a process started in this app process. Does not wait. " +
                "${ProcessTools.PROCESS_ID} is local:<uuid> or ssh:<deviceId>:<uuid>. " +
                "The process is gone if the app or SSH session died.",
            mapOf(
                ProcessTools.PROCESS_ID to ToolProperty(
                    "string",
                    "Id returned by ${ProcessTools.START_LOCAL} / ${ProcessTools.START_REMOTE}.",
                ),
            ),
            listOf(ProcessTools.PROCESS_ID),
        )
        tools += tool(
            ProcessTools.LIST,
            "List live local and remote processes Agora started in this app process, including " +
                "foreground (waiting=true) and background (waiting=false). Background processes " +
                "notify you with a new user message when they exit; use this only to inspect " +
                "what is still running. The process is gone if the app or SSH session died.",
            emptyMap(),
            emptyList(),
        )
        tools += tool(
            ProcessTools.WAIT,
            "Wait again on the same ${ProcessTools.PROCESS_ID}, up to wait_ms. Does not start a new process. " +
                "If still running, returns {running, ${ProcessTools.PROCESS_ID}} and partial output " +
                "(background again; ${ProcessTools.BACKGROUND_EXIT_NOTIFY}).",
            mapOf(
                ProcessTools.PROCESS_ID to ToolProperty(
                    "string",
                    "Id returned by ${ProcessTools.START_LOCAL} / ${ProcessTools.START_REMOTE}.",
                ),
                "wait_ms" to ToolProperty(
                    "integer",
                    "Wait budget in milliseconds (required, hard ceiling 295000).",
                ),
            ),
            listOf(ProcessTools.PROCESS_ID, "wait_ms"),
        )
        tools += tool(
            ProcessTools.STOP,
            "Stop a process started in this app process. Sends TERM then KILL to the local " +
                "process tree, or the SSH exec signal to the remote process group. " +
                "Returns stopped only after the process is no longer running. " +
                "Does not affect remote nohup or detached jobs the model started itself.",
            mapOf(
                ProcessTools.PROCESS_ID to ToolProperty(
                    "string",
                    "Id returned by ${ProcessTools.START_LOCAL} / ${ProcessTools.START_REMOTE}.",
                ),
            ),
            listOf(ProcessTools.PROCESS_ID),
        )
        return tools
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        ),
    )
}
