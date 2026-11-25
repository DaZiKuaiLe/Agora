# 远程 Shell

打开**设置 → Shell 设备**配置供代理工具使用的 SSH 设备。Conch 已废弃，不再作为在线后端；旧备份里残留的 Conch 字段会被忽略。

## SSH

可配置主机、端口、用户和主机密钥验证/固定。认证方式为登录密码或私钥（OpenSSH、PEM 或 PuTTY）二选一；加密私钥可另填可选口令。导入私钥使用应用内文件浏览器，需要全部文件访问权限，并会列出 `.ssh` 等隐藏文件夹。直接 SSH 不受共享 HTTP 代理控制。

远程文件的读、改、走 SFTP（`read_remote_file`、`str_replace_remote`、`write_remote_file`）。列一层目录用一次 SFTP `ls`（`list_remote_files`）。按名/按内容的递归搜索（`glob_remote_files`、`grep_remote`）在主机上跑 `find`/`grep` 或 PowerShell，没有这些能力就失败，不会把目录树下载到手机。短命令用 `execute_remote_command`。预计五秒以上、或要一边跑一边说话/开下一件时，用 `start_remote_process` 以及 get/wait/stop。短命令超时会被结束，不会升成进程。

## 确认

命令、写入、替换和传输可要求确认，或按服务器设为始终允许。将 SSH 视为高信任能力：远程账户权限决定模型能改什么。

参见 [代理工具](tools.md)、[网络代理](proxy.md) 与 [隐私与安全](privacy.md)。
