# Conch 废弃

Status: 2026-09-05。读者：后续改 Shell / 设备配置 / 工具的人。

## 结论

Conch 已认定废弃。新代码只走 **SSH + 本机**。不要为 Conch 补兼容、不要再长 `ShellDurableJobExecutor` 的 Conch 任务路径，也不要把同步 exec 超时升成 Conch 式 `job_id`。

方便时删除即可，不必为删这些代码单独开功能：

| 残留 | 说明 |
|---|---|
| `ConchBackend` | `ShellToolBackends.kt`；工具层已不再选用 `type=conch` |
| `ShellClient` | Conch HTTP / 密钥交换客户端 |
| `ShellDurableJobExecutor` 的 Conch 路径 | 新进程工具用进程内 `process_id`，不是 durable `job_id` |
| 设备字段 `serverUrl` / `apiKey` / `conchPublicKey` | 仅为 DataStore 解码旧配置保留；工具忽略 |
| `acknowledgeCommittedJobs` | 已是 no-op，调用点可随后去掉 |

配置里仍可能出现 `type=conch` 的旧设备。`ShellToolDefinitions` 只列举 `type=ssh`；这类设备不再出现在 `list_remote_shells`，也不会接到任何新工具。

## 替代

- 远程命令：`execute_remote_command` / `start_remote_process`
- 远程文件读/改/写：`read_remote_file` / `str_replace_remote` / `write_remote_file`（SFTP）
- 远程列一层目录：`list_remote_files`（一次 SFTP `ls`）
- 远程按名/按内容搜：`glob_remote_files` / `grep_remote`（主机 `find`/`grep` 或 PowerShell，失败不回退 SFTP）
- 本机：对应 `*_local_*` 与 `execute_local_command` / `start_local_process`
- 进程：`process_id`（`local:<uuid>` / `ssh:<deviceId>:<uuid>`），进程级；App 或 SSH 断了就没了

权威循环合同见 [agentic-loop-and-generation-requirements.md](agentic-loop-and-generation-requirements.md) §8。
