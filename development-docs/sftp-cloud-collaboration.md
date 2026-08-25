# SFTP 云-端协作（图片上传/下载 + 会话预览）

状态：已实现（2026-08-23）。SFTP 上传/下载工具、模型本地图片路径清单、
App 专属下载目录 + 同名增量改名、会话工具面板图片预览均已完成。

## 1. 需求背景

用户上传一张图片给大模型后，希望触发如下「云-端协作」流程：

```
用户传图 → 模型看到本地路径清单
  → 模型调 upload_file_to_server 把原图传到远程高压机器（SFTP 二进制直传）
  → 模型调 execute_shell_command 在远程机器上跑 ffmpeg / AI 生成
  → 模型调 download_file 把结果下载回手机的 App 专属目录
  → 会话的工具面板预览结果图片（点击可全屏）
```

关键约束：

1. **不经过 base64**。base64 会转码耗时、体积膨胀约 33%，大图成本高。
2. **与 shell 配套**。上传/下载与 `execute_shell_command` 共享同一台 SSH 服务器，
   模型上传后可用 shell 在同一台机器上处理。
3. **存储到 App 专属目录**（类似 QQ 文件），不是系统相册。
4. **同名自动增量改名**（`a.jpg` → `a(1).jpg`），避免覆盖。
5. **当前只做图片预览**；视频预览后续路径通了再做。

## 2. 协议选型：SFTP 而非 FTP / 自定义 HTTP

| 方案 | 结构化返回 | base64 | 用户部署成本 | 与 shell 绑定 | 结论 |
|---|---|---|---|---|---|
| FTP | ❌ 无 | — | 中途 | ❌ | 不采用 |
| 自定义 HTTP | ✅ | 需定制服务 | 高 | ❌ | 不采用 |
| **SFTP** | 文件天然落盘 | **直传字节** | **Linux ssd 自带** | ✅ 同一 SSH | ✅ 采用 |

SFTP（SSH File Transfer Protocol）基于 SSH 加密通讯，是 Linux 服务器内置的通用
文件传输协议。`SshClient` 已用 JSch 的 `ChannelSftp`，天然支持二进制直传。

## 3. 范围

- ✅ 图片：上传到远程、下载到 App 专属目录、会话工具面板预览。
- 🚫 视频：暂不实现，等路径通了后续再做（下载后只显示文件名/路径）。

## 4. 改动清单

### 4.1 `SshClient.kt` —— 新增 3 个方法

```kotlin
suspend fun uploadFile(localPath: String, remotePath: String): String?
suspend fun downloadFile(remotePath: String, localDir: String): String?
private fun incrementName(file: File): File
```

- `uploadFile`：SFTP `put` 原始字节流（`inputStream`），不经过 base64。
- `downloadFile`：SFTP `get` 下载到 `localDir`，同名自动增量改名。
- `incrementName`：`a.jpg` → `a(1).jpg` → `a(2).jpg`。

### 4.2 `ShellToolBackends.kt` —— `SshBackend` 暴露两个方法

委托给 `SshClient`：

```kotlin
suspend fun uploadFile(localPath: String, remotePath: String): String? =
    client.uploadFile(localPath, remotePath)
suspend fun downloadFile(remotePath: String, localDir: String): String? =
    client.downloadFile(remotePath, localDir)
```

### 4.3 `ShellToolProvider.kt` —— 两个工具分支

- `upload_file_to_server`：本地读图二进制上传。
- `download_file`：SFTP 下载到 App 专属目录，返回带 `images` 的
  `ToolExecutionResult`，让工具面板渲染预览。

### 4.4 `ShellToolDefinitions.kt` —— 两个中文工具定义

`upload_file_to_server(server, local_path, remote_path)` 与
`download_file(server, remote_path, target_dir)`。

### 4.5 让模型拿到本地图片路径

`ChatMessage` 新增非持久化字段 `imageAssetsText`；`ProviderMessageProjector`
在投影时对带图消息填 `image[N] = /data/user/0/.../img_x.jpg`。模型据此填
`local_path`。

## 5. 交互流程

```
用户传图 → ProviderMessageProjector 生成 imageAssetsText
  → 模型上传 upload_file_to_server(local_path=image[N], remote_path=/data/uploads/a.jpg)
  → execute_shell_command 处理
  → 下载 download_file(remote_path=/data/uploads/result.jpg)
  → SFTP 存到 filesDir/downloads/result(1).jpg（同名增量）
  → segment.toolImages 非空 → ToolImageResults 预览 → 点击 FullScreenMediaViewer
```

## 6. 关键复用

- `ToolImageResults` / `ToolImagePreview`（`ToolResultContent.kt`）用 Coil
  `AsyncImage` 渲染 `segment.toolImages`，点击进 `FullScreenMediaViewer`。
- `ToolImageStore` 只接受 `image/*`（图片），正好匹配「只做图片」范围。
- 工具面板仅在 `segment.toolImages.isNotEmpty()` 时渲染图片控件 —— 下载完成
  才显示，未下载则不显示。
