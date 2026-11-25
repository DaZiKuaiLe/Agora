package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext

/**
 * Builds the tool definitions exposed to the model.
 *
 * Remote terminals are SSH only. Local commands use [execute_local_command].
 * Remote images: download via SFTP into the app-private downloads dir, then [read_image].
 * Open a local file or http(s) URL in another app with [open_file].
 */
internal object ShellToolDefinitions {
    fun build(
        ctx: GenerationContext,
        packagedCliNames: List<String> = emptyList(),
    ): List<ToolDefinition> {
        if (!ctx.shellEnabled) return emptyList()
        val hasLocal = ctx.localCommandEnabled
        val remoteDeviceNames = ctx.shellDevices
            .filter { it.type == "ssh" }
            .map { it.name.ifBlank { "Untitled" } }
        if (remoteDeviceNames.isEmpty() && !hasLocal) return emptyList()

        val deviceNamesStr = remoteDeviceNames.joinToString(", ")
        val serverPropDesc = when {
            remoteDeviceNames.isEmpty() ->
                "SSH remote terminal name."
            remoteDeviceNames.size == 1 ->
                "The SSH remote terminal name. Omit to use the only available one: ${remoteDeviceNames[0]}."
            else ->
                "The SSH remote terminal name. Use list_remote_shells to see available terminals: $deviceNamesStr."
        }
        val remoteRequiredParams =
            if (remoteDeviceNames.size == 1) listOf("command", "timeout_ms")
            else listOf("command", "server", "timeout_ms")
        val fileServerProperty = when {
            remoteDeviceNames.isEmpty() ->
                ToolProperty("string", "The SSH remote terminal name.")
            remoteDeviceNames.size == 1 ->
                ToolProperty("string", "The SSH remote terminal name (optional, defaults to the only available one).")
            else ->
                ToolProperty("string", "The SSH remote terminal name. Available: $deviceNamesStr.")
        }
        val fileRequired = if (remoteDeviceNames.size <= 1) emptyList<String>() else listOf("server")

        val shellTools = buildList {
            if (remoteDeviceNames.isNotEmpty()) {
                add(
                    ToolDefinition(
                        function = ToolFunction(
                            name = "list_remote_shells",
                            description =
                                "List configured SSH remote terminals and their shell fingerprint. " +
                                    "Call this before remote exec or file tools when the remote OS/shell is unclear. " +
                                    "Use each device's command_syntax (not guesses) for execute_remote_command / start_remote_process: " +
                                    "posix | cmd | powershell. When shell_preference is auto and shell is cmd, " +
                                    "Agora still runs payloads via PowerShell — command_syntax will be powershell. " +
                                    "os_hint is windows | unix | unknown. File read/search/list/edit use *_remote_* tools, not this.",
                            parameters = ToolParameters(properties = emptyMap(), required = emptyList()),
                        ),
                    ),
                )
                add(
                    ToolDefinition(
                        function = ToolFunction(
                            name = "execute_remote_command",
                            description =
                                "Execute a short command on an SSH remote terminal (build, test, git). " +
                                    "Do not use this to list, search, or read files — use list_remote_files / glob_remote_files / grep_remote / read_remote_file. " +
                                    "Match command syntax to command_syntax from list_remote_shells (posix / cmd / powershell). " +
                                    "If the command may take ${ProcessTools.LONG_PROCESS_SECONDS}s or more, duration is unknown, or you need to speak first: start_remote_process. Do not sleep. " +
                                    "Binary or large files: upload_file_to_server / download_file. " +
                                    "If this call times out, the command is killed; it is not promoted to a process.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "command" to ToolProperty("string", "The command to execute."),
                                    "server" to ToolProperty("string", serverPropDesc),
                                    "timeout_ms" to ToolProperty(
                                        "integer",
                                        "Required. Wait budget in milliseconds (hard ceiling 295000ms inside the tool call).",
                                    ),
                                    "workdir" to ToolProperty("string", "Working directory (optional)."),
                                    "ok_exit" to ToolProperty(
                                        "array",
                                        "Optional. If the exit code is in this set, return code and byte lengths only; the body is stored for query_detail_store.",
                                        items = ToolProperty("integer", "Accepted exit code."),
                                    ),
                                ),
                                required = remoteRequiredParams,
                            ),
                        ),
                    ),
                )
                add(
                    ToolDefinition(
                        function = ToolFunction(
                            name = "upload_file_to_server",
                            description =
                                "用 SFTP 把手机本地文件二进制上传到 SSH 远程终端（不经过 base64）。local_path 填手机端文件绝对路径；remote_path 填远程终端目标路径。上传成功后可用 execute_remote_command 在同一台终端上处理该文件。\n\n如果上传的是图片：用户消息里看到的图可能是缩放预览，而 local_path 指向原图。\n\n只支持 type=ssh 的远程终端。",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "local_path" to ToolProperty(
                                        "string",
                                        "手机端本地文件绝对路径。",
                                    ),
                                    "remote_path" to ToolProperty(
                                        "string",
                                        "远程终端上的目标路径。Linux/macOS 用 POSIX 绝对路径；Windows 可用 C:\\Users\\...、C:/Users/... 或 /C:/Users/...。",
                                    ),
                                    "server" to fileServerProperty,
                                ),
                                required = listOf("local_path", "remote_path") + fileRequired,
                            ),
                        ),
                    ),
                )
                add(
                    ToolDefinition(
                        function = ToolFunction(
                            name = "download_file",
                            description =
                                "用 SFTP 把远程终端上的文件下载到手机。remote_path 填远程路径。\n\n" +
                                    "默认下载到 App 专属下载目录（推荐；无需全部文件访问权限）。" +
                                    "若随后要用 read_image 查看远程图片或 open_file 打开远程文件，请省略 target_dir 或显式使用该专属下载目录，再用返回的本地绝对路径调用对应工具。" +
                                    "同名文件自动改名为 name(1).ext（不覆盖）。只支持 type=ssh 的远程终端。",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "remote_path" to ToolProperty(
                                        "string",
                                        "远程终端上的文件路径（Windows 可用 C:\\Users\\... 或 /C:/Users/...）。",
                                    ),
                                    "server" to fileServerProperty,
                                    "target_dir" to ToolProperty(
                                        "string",
                                        "手机端目标目录（可选）。默认 App 专属下载目录；一般读图场景请使用默认目录。授予全部文件访问后才可指定共享存储路径。",
                                    ),
                                ),
                                required = listOf("remote_path") + fileRequired,
                            ),
                        ),
                    ),
                )
            }
        }

        val cliList = packagedCliNames.filter { it.isNotBlank() }.distinct()
        val cliDesc = if (cliList.isEmpty()) {
            "`node`（JS）、`ffmpeg`/`ffprobe`（音视频/H.264）、`rg`（全文搜索）、`busybox` 及 applet（`tar`/`awk`/`sed`/`find` 等）"
        } else {
            cliList.joinToString("、") { "`$it`" } +
                "（`node`=JS；`ffmpeg`/`ffprobe`=音视频含 libx264；`rg`=ripgrep；`tar`/`awk`/`sed`/`find`/… 经 busybox）"
        }
        val localTools = if (hasLocal) {
            listOf(
                ToolDefinition(
                    function = ToolFunction(
                        name = "execute_local_command",
                        description =
                            "在本机执行短命令（构建、测试、git，以及已打包 CLI：$cliDesc）。" +
                                "不要用本工具列举、搜索或读写文件；请用 list_local_files / glob_local_files / grep_local / read_local_file / str_replace_local / write_local_file。" +
                                "预计 ${ProcessTools.LONG_PROCESS_SECONDS} 秒以上、时长未知、或要先说话时必须用 start_local_process，禁止 sleep。超时会杀死进程，不会升为进程工具。" +
                                "命令名会解析到 native 库目录；busybox applet 会改写成 busybox 调用。" +
                                "共享存储需全部文件访问；系统锁死路径始终不可访问。返回 stdout/stderr 与退出码。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "command" to ToolProperty(
                                    "string",
                                    "要执行的本地命令。可直接写 node / ffmpeg / ffprobe / rg / tar / awk / find 等已打包 CLI 名。",
                                ),
                                "timeout_ms" to ToolProperty(
                                    "integer",
                                    "命令执行超时上限（毫秒，默认 295000，上限受工具调用约束）。超时会杀死进程。",
                                ),
                                "workdir" to ToolProperty(
                                    "string",
                                    "工作目录（可选）。也可写共享存储路径（需已授权）。",
                                ),
                                "ok_exit" to ToolProperty(
                                    "array",
                                    "可选。若退出码在此集合内，只返回码和字节长度，正文写入细节库。",
                                    items = ToolProperty("integer", "Accepted exit code."),
                                ),
                            ),
                            required = listOf("command", "timeout_ms"),
                        ),
                    ),
                ),
                ToolDefinition(
                    function = ToolFunction(
                        name = "request_all_files_access",
                        description =
                            "请求用户授予本机「全部文件访问」（共享存储读写，Android 11+ 会打开系统设置）。" +
                                "若工具结果仍是权限不足，再调用本工具弹出授权流程，完成后重试原操作。" +
                                "不改变系统锁死路径的限制。返回 granted / denied / dismissed / unavailable。",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "reason" to ToolProperty(
                                    "string",
                                    "可选。向用户简短说明为何需要该权限。",
                                ),
                            ),
                            required = emptyList(),
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }

        // Available whenever shell tools are on (local and/or SSH): after download_file or path refs.
        val readImageTool = ToolDefinition(
            function = ToolFunction(
                name = "read_image",
                description =
                    "读取手机本机上的图片文件，作为视觉上下文供模型查看（会走与附件相同的图片持久化与转写流程）。" +
                        "path 必须是本机绝对路径（例如用户路径引用展开后的路径，或 download_file 返回的本地路径）。" +
                        "远程服务器上的图片：先用 download_file 下载到 App 专属下载目录（省略 target_dir），再用返回的本地路径调用本工具。" +
                        "不要用本工具读取非图片文件；大文件/二进制请用路径作为句柄，通过命令或 SFTP 处理。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty(
                            "string",
                            "本机图片文件的绝对路径。",
                        ),
                    ),
                    required = listOf("path"),
                ),
            ),
        )
        val openFileTool = ToolDefinition(
            function = ToolFunction(
                name = OpenFile.TOOL,
                description =
                    "在系统应用中打开本机文件或 http(s) URL（阅读器、播放器、安装器、浏览器）。" +
                        "path 为本机绝对路径，或 http:// / https:// 链接。" +
                        "远程 SSH 上的文件必须先 download_file，再用返回的本地路径调用本工具。" +
                        "不要用 file://、content:// 或其他 scheme。" +
                        "共享存储未授权时先 request_all_files_access。" +
                        "apk 会交给系统安装界面（需允许 Agora 安装未知应用）。" +
                        "ok:true 只表示已交给系统应用，不保证内容已打开或可访问。",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty(
                            "string",
                            "本机文件绝对路径，或 http/https URL。",
                        ),
                    ),
                    required = listOf("path"),
                ),
            ),
        )

        return shellTools + localTools + listOf(readImageTool, openFileTool)
    }
}
