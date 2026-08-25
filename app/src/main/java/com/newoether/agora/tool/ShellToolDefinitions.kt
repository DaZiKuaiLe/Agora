package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext

internal object ShellToolDefinitions {
    fun build(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled) return emptyList()
        if (ctx.shellDevices.isEmpty() && !ctx.sandboxEnabled) return emptyList()

        val hasLocal = ctx.sandboxEnabled
        val allDeviceNames = buildList {
            if (hasLocal) add("Local Sandbox")
            addAll(ctx.shellDevices.map { d -> "\"${d.name}\"" })
        }
        val deviceNamesStr = allDeviceNames.joinToString(", ")

        val serverPropDesc = if (allDeviceNames.size == 1) {
            "The shell server name (optional, defaults to the only available server: ${allDeviceNames[0]})."
        } else {
            "The shell server name. Use list_shells to see available servers: $deviceNamesStr."
        }
        // timeout_ms is REQUIRED: the model must decide how long the tool call should wait. Conch
        // foreground execution is durable from the start, so expiry returns its job id without
        // killing or restarting the command.
        val shellRequiredParams =
            if (allDeviceNames.size == 1) listOf("command", "timeout_ms")
            else listOf("command", "server", "timeout_ms")

        val conchDeviceNames = ctx.shellDevices
            .filter { it.type != "ssh" }
            .map { it.name.ifBlank { "Untitled" } }
        val shellTools = buildList {
            add(ToolDefinition(function = ToolFunction(
                name = "list_shells",
                description = "List configured shell servers including the local sandbox (if enabled).",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "execute_shell_command",
                description = "Execute a shell command. Set background=true for a durable Conch job that survives client disconnects.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "command" to ToolProperty("string", "The shell command to execute."),
                        "server" to ToolProperty("string", serverPropDesc),
                        "timeout_ms" to ToolProperty("integer", "Required. Foreground wait budget in milliseconds (hard ceiling 295000ms inside the tool call). On Conch, a command still running at that point continues as the same durable job and returns its job_id; it is never killed or restarted. With background=true this instead bounds the durable job's runtime (up to Conch policy)."),
                        "workdir" to ToolProperty("string", "Working directory (optional)."),
                        "background" to ToolProperty("boolean", "Start a durable background job on Conch and return its job_id immediately (optional, default false)."),
                    ),
                    required = shellRequiredParams
                )
            )))
            if (conchDeviceNames.isNotEmpty()) {
                val jobServerDescription = if (conchDeviceNames.size == 1) {
                    "Conch server name (optional; defaults to ${conchDeviceNames.single()})."
                } else {
                    "Conch server name. Available: ${conchDeviceNames.joinToString(", ")}."
                }
                val jobRequired = if (conchDeviceNames.size == 1) {
                    emptyList()
                } else {
                    listOf("server")
                }
                add(ToolDefinition(function = ToolFunction(
                    name = "list_shell_jobs",
                    description = "List durable background shell jobs on a Conch server.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "get_shell_job",
                    description = "Get status and bounded output for a durable Conch shell job. Prefer wait_for_job for blocking use.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id") + jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "wait_for_job",
                    description = "Block until a durable Conch shell job finishes or timeout_ms elapses while updating one shell-style output block with bounded full cumulative snapshots. A timed-out result includes the current full retained output and leaves the job running; call wait_for_job again to keep waiting.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "timeout_ms" to ToolProperty("integer", "Required. Maximum time to block in milliseconds before returning (whether or not the job finished). The hard ceiling for a single call is ${ShellDurableJobExecutor.maxWaitMs(ctx)}ms; larger values are clamped to it and the result says so. To wait longer, call wait_for_job again."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id", "timeout_ms") + jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "stop_shell_job",
                    description = "Stop a running durable Conch shell job and its process tree.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id") + jobRequired,
                    ),
                )))
            }
        }

        val fileServerProperty = if (allDeviceNames.size == 1) {
            ToolProperty("string", "The shell server name (optional, defaults to the only available server).")
        } else {
            ToolProperty("string", "The shell server name. Available: $deviceNamesStr.")
        }
        val fileRequired = if (allDeviceNames.size == 1) emptyList<String>() else listOf("server")

        val fileTools = listOf(
            ToolDefinition(function = ToolFunction(
                name = "file_read",
                description = "Read a file from a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "server" to fileServerProperty,
                        "offset" to ToolProperty("integer", "Byte offset (optional)."),
                        "limit" to ToolProperty("integer", "Max bytes to read (optional, default 1MB).")
                    ),
                    required = listOf("path") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_write",
                description = "Write content to a file on a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "content" to ToolProperty("string", "Content to write."),
                        "server" to fileServerProperty
                    ),
                    required = listOf("path", "content") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_edit",
                description = "Edit a file on a shell server or local sandbox by replacing old_string with new_string.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "old_string" to ToolProperty("string", "The exact text to find and replace."),
                        "new_string" to ToolProperty("string", "The replacement text."),
                        "server" to fileServerProperty,
                        "replace_all" to ToolProperty("boolean", "Replace all occurrences (optional, default false).")
                    ),
                    required = listOf("path", "old_string", "new_string") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_glob",
                description = "List files on a shell server or local sandbox matching a glob pattern.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Glob pattern matched against file names (e.g. '*.go', '*.md')."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "Base directory for the search (optional)."),
                        "depth" to ToolProperty("integer", "Max directory levels to search below 'path': 1 = base directory only, higher values recurse deeper, 0 = unlimited recursion. Omit for the server default.")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_grep",
                description = "Search for a regex pattern in files on a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Regular expression pattern to search for."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "File or directory to search in (optional)."),
                        "glob" to ToolProperty("string", "Filter files by glob pattern (optional).")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "upload_file_to_server",
                description = "用 SFTP 把手机本地文件二进制上传到 SSH 服务器（不经过 base64）。local_path 填手机端文件绝对路径（来自用户消息里的 [本地图片 / 本地文件] 清单，如 image[0] 或 file[0] 的完整路径）；remote_path 填服务器目标路径。上传成功后可用 execute_shell_command 在同一台服务器上处理该文件。\n\n如果上传的是图片：你在用户消息里看到的图是为节省 token 而缩放后的版本，而 local_path 指向的是原始分辨率原图，服务端收到的也是原图；两者分辨率不一致属正常现象，请以服务端实际文件为准。\n\n只支持 type=ssh 的服务器。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "local_path" to ToolProperty("string", "手机端本地文件绝对路径（来自 [本地图片 / 本地文件] 清单，如 /data/user/0/.../img_x.jpg）。"),
                        "remote_path" to ToolProperty("string", "服务器上的目标绝对路径，如 /data/uploads/img_x.jpg。"),
                        "server" to fileServerProperty,
                    ),
                    required = listOf("local_path", "remote_path") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "download_file",
                description = "用 SFTP 把服务器上的文件下载到手机 App 专属目录。remote_path 填服务器路径；target_dir 可省略（默认 App 专属下载目录）。同名文件自动改名为 name(1).ext（不覆盖）。\n\n如果下载的是图片，会话里会自动预览；其它文件只返回本地路径，不会在工具面板预览。只支持 type=ssh 的服务器。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "remote_path" to ToolProperty("string", "服务器上的文件绝对路径（如为图片，下载后会预览）。"),
                        "server" to fileServerProperty,
                        "target_dir" to ToolProperty("string", "手机端目标目录（可选，默认 App 专属下载目录）。"),
                    ),
                    required = listOf("remote_path") + fileRequired
                )
            )),
        )

        val imageTools = if (conchDeviceNames.isEmpty()) {
            emptyList()
        } else {
            val imageServerDescription = if (conchDeviceNames.size == 1) {
                "Conch server name (optional; defaults to ${conchDeviceNames.single()})."
            } else {
                "Conch server name. Available: ${conchDeviceNames.joinToString(", ")}."
            }
            val imageRequired = if (conchDeviceNames.size == 1) {
                listOf("path")
            } else {
                listOf("path", "server")
            }
            listOf(
                ToolDefinition(
                    function = ToolFunction(
                        name = "view_image",
                        description =
                            "Load an image from a Conch device and return it as visual context.",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "path" to ToolProperty(
                                    "string",
                                    "Absolute path to the image file.",
                                ),
                                "server" to ToolProperty("string", imageServerDescription),
                            ),
                            required = imageRequired,
                        ),
                    ),
                ),
            )
        }

        return shellTools + fileTools + imageTools
    }
}
