# 文件工具：list / glob / grep

Status: 实现参照（已落地），2026-09-10。  
读者：后续改文件工具的人（含大模型）。改行为时先改本文，再改代码。

本文记录 2026-09 这一轮的产品定案：三个动词怎么分工、远程在哪执行、失败怎么报、以及干活机实测反馈哪些采纳。  
**不是** `read` / `write` / `str_replace` 的合同；那三套本轮不动。

相关：测试写法见 [testing.md](testing.md)；Conch 已废弃见 [conch-deprecation.md](conch-deprecation.md)；用户手册 `docs/en/shell.md` / `docs/zh/shell.md` 落地时同步。

---

## 1. 为什么改（背景，勿写回旧行为）

旧实现把「同一套扫描算法」套在本地 nio 和远程 SFTP 上：

- `list_*` 默认**无限递归**，靠参数 `depth=1` 才像 `ls`。模型看工具名会当成列一层，不传 depth 就扫整棵树。
- `grep_remote` **不跑远程 grep**：SFTP 把文件拉到手机再 Java 正则。二十来个文件可耗尽 30s 时间预算。
- 「按文件名找」没有独立工具，藏在 list 的 `glob` 里，和列目录混在一起。

原则（已确认）：

1. **接口统一、算法按场地。** `FileAccess` 仍给上层同一套出口，方便以后 UI / 其它模块调用。本地和远程的**实现**必须语义等价（工作发生在文件所在侧），不是「远程当劣质磁盘、把树搬到手机再搜」。
2. **常用分叉用工具名，冷门才进参数。** 模型应先看名字知道职能，不必试错后再读长描述。
3. **名字对齐 Linux / Cursor**：`list` ≈ `ls` / `list_dir`；`glob` ≈ Cursor `Glob`；`grep` ≈ Cursor `Grep` / `grep -r`。除非他们没有而我们确实要拆，才按现有 `*_local_*` / `*_remote_*` 规范衍生。
4. **远程查询失败就是失败。** 不要 SFTP 静默回退。模型拿到原因后可自己写命令、写脚本、或告诉用户。这是模型的异常处理，不是工具的降级路径。

---

## 2. 三个动词（名字即职能）

本地 / 远程前缀保持现有规范。**不要**再做 `*_recursive` 第二套名字：递归性跟着动词走。

| 工具名 | 对齐 | 职能 | 默认范围 |
|---|---|---|---|
| `list_local_files` / `list_remote_files` | Linux `ls`、Cursor `list_dir` | 列**一个目录**里的条目 | **只一层** |
| `glob_local_files` / `glob_remote_files` | **新工具**，Cursor `Glob` | 按**文件名 glob**找路径 | **递归** |
| `grep_local` / `grep_remote` | Cursor `Grep` | 按**内容正则**找行 | **递归** |

禁止用 `execute_*` / `ls` / `find` / `rg` / `cat` 当这套文件 API（提示词与工具描述写清）。模型仍可用 `execute_*` 在工具失败后自救，那是另一条路。

### 2.1 `list_*`

- 就是看这一层有什么。**去掉 `depth`，去掉 `glob`，去掉 `no_ignore`。**
- 结果必须含 **file 和 dir**（旧实现几乎只收文件，不像 `ls`，模型看不到下一步能进哪些文件夹）。
- 同一层：**目录排在文件前面**。避免一层里几百个缓存文件把 200 条配额吃光、目录名被挤到后面。
- 可选冷门参数 `dirs_only: true`：只列子目录（探索结构）。
- 每条带 `size`、`mtime_ms`（目录 size 可省略或 0）。本地用 `length` / `lastModified`；远程一层 SFTP `ls` 的 attrs 里已有，不要再为每个条目另开通道 stat。
- `path` 可空 = 该场地默认根（本地 app files / 远程 `pwd`）。`path` 指向普通文件 = 只返回这一条。
- 分页：`offset`；每页最多 200。一层通常远小于旧的 walk 800，**list 不再使用递归 walk_cap。**
- `node_modules` 等作为**这一层的目录名应出现**；不往里走（本来就不递归）。

### 2.2 `glob_*`（新）

- 按名字找文件，默认递归。主参数 `pattern`（**glob，不是正则**）。
- 可选：`path`（搜索根）、`no_ignore`、`offset`、`ignore`（额外目录名数组，见 §5）。
- glob 语法：`*` 一段，`**` 跨目录，`?` 单字符。pattern **不含 `/` 时当成任意深度基名**（`*.kt` ≡ `**/*.kt`），与 Cursor 习惯、旧 list 预处理一致。
- 返回匹配到的路径（文件为主；目录名被模式命中也可返回）。
- ignore denylist 默认生效；`no_ignore=true` 才进入那些目录。
- 分页与树扫描上限：页 200；树 cap 仍约 800（或远程 `find` 截断并设 `truncated_reason`）。

### 2.3 `grep_*`

- 按内容搜，默认递归。主参数 `pattern`（**内容正则，不是文件名**）。
- 可选：`path`、`glob`（过滤器，相当于 `rg -g`，不是另一种动词）、`no_ignore`、`offset`、`ignore`。
- `path` 指向单个文件 = 只搜该文件。
- **不拆**非递归 grep。一层目录的内容搜：窄 `path`，或先 glob 再读。
- 上限沿用：每页 50 命中、最多扫 200 文件、单文件超过 500KB 跳过并计数、本地 30s 时间预算。远程用命令侧近似；对不上的跳过字段可 0，但分页 / incomplete 必须在。
- 成功 JSON 必须带 **`engine`**：`java` | `grep` | `powershell`（或实际用到的名字）。
- 本地：Java `Regex`（`FileTextOps.compilePattern`）。远程：**不要**先按 Java 编译再发出去（会拒掉合法 grep 模式，或放行远程跑不了的 Java 模式）。工具描述写明方言随 `engine`。

---

## 3. `FileAccess`（封装留下，算法拆开）

上层工具名拆分；底层仍两套 backend，出口同构。

```
list(path, offset, dirsOnly) -> 一层 entries（path, type, size?, mtime_ms?）
glob(pattern, path, noIgnore, ignore, offset) -> 递归路径
grep(pattern, path, glob, noIgnore, ignore, offset) -> 递归命中行
```

删掉 list 上的 `glob` / `depth` / `noIgnore`。

播放远程视频、`read_*` 取字节：远程返回 SFTP 流是对的（数据不必先落成另一份完整本地文件）。  
**grep/glob 不是这种操作**：它们是对着一棵树的查询，只应传回命中。把整文件搬到手机再搜，与「本地返回本地流、远程返回 SFTP 流」不是同一类封装。

---

## 4. 实现：工作发生在文件所在侧

### 4.1 本地

| 工具 | 实现 |
|---|---|
| list | `File.listFiles()` 一层；dir 优先；可选只 dir |
| glob | 本机 walk + `PortableGlobMatcher` |
| grep | 本机 Java 正则；可选 glob 先过滤路径 |

本机已打包 `rg` / busybox `find`，那是给 `execute_local_command` 用的。`grep_local` / `glob_local_files` **不**改走 shell，以免和工具出口、ignore、分页两套逻辑。

### 4.2 远程

| 工具 | 实现 | 失败 |
|---|---|---|
| `list_remote_files` | **一次 SFTP `ls` 那一层**（只要名字、是否目录、size/mtime；不下载文件体） | SFTP 失败 → `error` |
| `glob_remote_files` | 远端命令：POSIX 优先 `find`（prune 与 denylist 对齐）；否则 PowerShell `Get-ChildItem -Recurse` | **禁止**再 SFTP 递归 walk 凑合 |
| `grep_remote` | 远端命令：优先 `grep`（须能递归、能 `--include` 或等价）；否则 PowerShell `Select-String` | **禁止** SFTP get 再本地正则 |

`list_remote` 用 SFTP readdir 是有意例外：一层目录列举等价于本地 `File.listFiles()`，不拉文件体；`ls` 文本在空格/locale/列格式上不是稳定 API。这**不是** grep 那种「拉内容来凑」。

`SshClient.fileGrep()` 现有「先 exec grep、失败再 SFTP」**不得**接到 `grep_remote`。

探测可按 host 缓存（类似 `command_syntax`）。探测失败 = **这一次工具失败**，不是换通道。  
pattern / glob / path 按已探测 shell **quoting**，避免未确认的注入（文件查询不走写文件确认门）。

`read_*` / `write_*` / `str_replace_*` 继续 SFTP。

---

## 5. 忽略目录（denylist）

固定按**目录名**匹配（大小写不敏感），**不读 `.gitignore`**（SFTP/远程成本，本轮不做）。

在现有名单上扩容（干活机 `C:/apps` 被 `douyin_profile/Default/Cache` 撑爆 200 条）。现有已有例如：`.git` `.svn` `.hg` `node_modules` `bower_components` `__pycache__` `.venv` `venv` `.tox` `.mypy_cache` `.pytest_cache` `.gradle` `.next` `dist` `build` `out` `target` `logs` `log` `.cache`。

**本轮增加：** `cache` `gpucache` `code cache` `cache_data` `appdata` `site-packages` `coverage`。

**不加**单独的 `Default`（误伤普通项目目录）。`Default/Cache` 靠目录名 `cache` 跳过。

- glob / grep：默认 prune 这些目录；`no_ignore=true` 关闭。
- 可选参数 `ignore`：额外目录名数组（JSON 数组，不要逗号长字符串），并进 prune。仅 glob / grep。list 一层不需要。
- 命中的忽略目录名回传 `ignored_dirs`（条数上限沿用现有 report cap）。

---

## 6. 不完整结果（P1，正确性）

干活机 `/root` 上 `grep`：30s 后 `scanned_files:0` + `truncated_reason:time_budget` + 空 matches。时间耗在**遍历目录树**，一个文件内容都没读。模型把「没扫到」误判成「不存在」（曾在搜 scene 源码时踩过）。

字段 `truncated` / `scanned_files` 当时已有，仍不够醒目。出口必须让「空结果不可信」无法忽视：

| 字段 | 含义 |
|---|---|
| `incomplete` | 凡截断（时间 / walk_cap / 远程命令超时 / 文件 cap）为 `true` |
| `phase` | `walk` \| `scan` \| `done`。时间打在遍历上则为 `walk` |
| `note` | **仅当 matches/paths 为空且 incomplete**：固定短句，例如 `no matches yet; walk/search did not finish — do not treat as absent` |
| `truncated` / `truncated_reason` / `remaining_is_lower_bound` | 保留 |

`truncated_reason` 值沿用：`page_cap` `file_cap` `walk_cap` `time_budget`。

远程改为真 grep 后，较少出现「手机上 walk 30s 零扫描」；本地 glob/grep、以及远程超时 0 命中，仍要这套字段。

---

## 7. JSON 出口（发给模型的结构）

成功均 `ok: true`。`error` 走现有 `jsonError`（`ok: false` + 原因字符串）。原因必须让模型能接着干，例如：无 grep、BusyBox 不够、正则被引擎拒绝、权限、超时。

**list**

```json
{
  "type": "list_local_files",
  "entries": [
    { "path": "/a/src", "type": "dir" },
    { "path": "/a/readme.md", "type": "file", "size": 120, "mtime_ms": 0 }
  ],
  "returned": 2,
  "remaining": 0,
  "offset": 0,
  "ok": true
}
```

dir 优先。可有 `truncated` / `truncated_reason`。不要只给 `files: string[]`。

**glob**

路径数组 + `returned` / `remaining` / `offset` / `ignored_dirs` / `incomplete` / `phase` / `note`（空且未完成时）。

**grep**

现有 `matches: [{path,line,text}]` + `scanned_files` `skipped_oversize` `skipped_binary` `ignored_dirs` + **`engine`** + incomplete 一组。

---

## 8. 模型侧文案（Default + 工具第一句）

`FileSearchPolicy.POLICY` 改为三句职能，去掉「两者都递归、list 可传 depth=1」：

- 看一层目录（含文件和子目录名）：`list_local_files` / `list_remote_files`
- 按名字找文件（glob，递归）：`glob_local_files` / `glob_remote_files`
- 按内容搜（正则，递归；`glob` 只过滤文件）：`grep_local` / `grep_remote`
- 不要用 `execute_*` 或 `ls`/`find`/`rg` 当文件 API
- 空结果且 `incomplete`：不得当成「不存在」

工具 description 第一句必须能从名字对上职能。`grep_remote` 删除 “Does not run remote grep/bash” / “over SFTP”。

用户手册：远程 **读/改/写** 仍写 SFTP；list 一层 SFTP readdir；glob/grep 为远程命令，失败不回退。

UI 字符串、`MessageItemToolLabels`、`ToolPresentation`、`FileToolDefinitions.NAMES`、`ToolTiming`、shell 工具描述里的「请用 list/grep」清单：补上 glob。

---

## 9. 干活机实测哪些采纳（2026-09 模型反馈）

实测发生在**旧工具面**（list 仍有 depth、grep 仍 SFTP 拉文件）。新字段 `ignored_dirs` / `truncated_reason` 等方向正确。

| 编号 | 反馈 | 定案 |
|---|---|---|
| P1 | 空 grep + time_budget 会当成「没有」 | **必须做**：`incomplete` + `phase` + 空结果 `note` |
| P2 | 噪音名单不够；要可配 | **扩容 denylist**；glob/grep 可选 `ignore[]`。不做 `.gitignore` |
| P3 | 未传 depth 默认 2 层 | **不采纳**。list 一层、glob/grep 递归，去掉 depth |
| P4 | 200 条被单目录吃光；要 dirs_only / 每目录限量 | list **dir 优先** + 可选 `dirs_only`。glob 不做每目录限量 |
| P5 | list 附带 size/mtime | **做**（一层成本低） |

另：`list` depth=1 在干活机上已证明一层又快又干净——这正是新 list 的默认，不再藏在参数里。

---

## 10. 明确不做（本轮与否决）

- glob / grep 失败后改 SFTP walk 或 get 再搜
- list 默认递归；保留 `depth`；默认 2 层
- `list_recursive` / `grep` 非递归独立工具
- 忽略名单加入光秃的 `Default`
- 读 `.gitignore`
- `read_*` 拆 range、`str_replace_*` 拆 `replace_all`（以后可另开文档）
- 会话搜索 AND / 「哪个词没命中」（`search_current_conversation` 另一套，不是本文件工具）
- 把 `FileAccess` 拆掉、让模型只靠 `execute_*` 做文件 API

---

## 11. 测试（不变量，不锁提示词原文）

权威：[testing.md](testing.md)。表驱动。不要 `*SourceContractTest`、不要 `File.readText()` 扫 `app/src/main`。

至少覆盖：

- **list 本地**：一层；含 dir；不列出子目录里的文件；dir 排在 file 前；`dirs_only` 只有 dir；条目有 size/mtime；`node_modules` 作为 dir 出现在该层。
- **glob 本地**：`*.kt` 能打到深层；默认跳过 vendor/cache 类目录名；`no_ignore` 能进去；可选 `ignore` 生效。
- **grep 本地**：单文件 / 目录 / 分页 / 过大跳过 / 时间预算。walk 超时：`incomplete=true`、`phase=walk`、`scanned_files=0`、空 matches、有 `note`。
- **远程 grep/glob**：命令 quoting；无引擎时 `error` 且**不**读文件体；list 远程一层 `entries` 含 `type`。
- **JSON**：list 用 `entries`+`type`；grep 带 `engine`；error 走 `jsonError`。

日常编译见 [testing.md](testing.md)：`JAVA_HOME` = Android Studio JBR；`gradlew` 不加 `--no-daemon`、不在命令里写代理；`:app:compilePlayDebugKotlin` + 触及类 `--tests`。单文件 ≤999 行。

---

## 12. 主要改动面（落地时）

- `FileAccess.kt`、`LocalFileAccess.kt`、`SftpFileAccess.kt`
- `FileToolDefinitions.kt`、`FileToolExecutor.kt`
- `FileSearchPolicy.kt`、`FileTextOps.kt`、`FileSearchIgnore.kt`
- `SshClient.kt`（list 一层 ls；glob/grep 走 exec；切断接到工具的 SFTP grep 回退）
- 远程 glob/grep 的命令构造与输出解析（控制单文件行数；quoting）
- Default 提示、shell 工具描述、`docs/en/shell.md`、`docs/zh/shell.md`
- UI：`strings.xml` / `values-zh`、`MessageItemToolLabels`、`ToolPresentation`、`ToolTiming`
- 测试：`LocalFileAccessTest`、`FileSearchPolicyTest`、`FileSearchIgnoreTest`、`ToolProviderTests` 名字表等

---

## 13. 一句话

`list` = 一层 `ls`（远程一次 SFTP readdir，带 dir/file/size/mtime）；`glob` = 递归按名找（远程 `find`/PS，失败即失败）；`grep` = 递归按内容找（本地 Java，远程 grep/PS，失败即失败）。空结果且未扫完必须标 `incomplete`，不得当成不存在。
