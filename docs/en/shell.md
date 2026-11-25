# Remote Shell

Open **Settings → Shell Devices** to configure SSH devices used by agentic tools. Conch is deprecated and is no longer offered as a live backend; leftover Conch fields in old backups are ignored.

## SSH

Configure host, port, user, and host-key verification/pinning. Authentication is either a login password or a private key (OpenSSH, PEM, or PuTTY), not both. Encrypted keys may include an optional passphrase. Import a key with the in-app file browser; this needs all-files access and lists hidden folders such as `.ssh`. Direct SSH transport is separate from Agora's shared HTTP proxy.

Remote file read, write, and edit use SFTP (`read_remote_file`, `str_replace_remote`, `write_remote_file`). Listing one folder uses one SFTP `ls` (`list_remote_files`). Recursive name and content search (`glob_remote_files`, `grep_remote`) run `find`/`grep` or PowerShell on the host and fail if those tools are unavailable — they do not download the tree to the phone. Short commands use `execute_remote_command`. Commands that may take five seconds or more, or that should keep running while the model talks or starts other work, use `start_remote_process` plus get/wait/stop. A timed-out short command is stopped; it is not turned into a process.

## Confirmation

Commands, writes, replaces, and transfers can require confirmation or be always allowed per configured server. Treat SSH as a high-trust capability: the remote account's permissions define what the model can change.

See [Agentic Tools](tools.md), [Network Proxy](proxy.md), and [Privacy & Security](privacy.md).
