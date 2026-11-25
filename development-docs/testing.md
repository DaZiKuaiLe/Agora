# Agora 测试怎么用、怎么写

Status: 维护约定，2026-10-21。  
读者：后续改代码的人（含大模型）。权威入口仍是 [`development/README.md`](../development/README.md)；本文只管测试。

本仓库由大模型持续改代码。测试要挡住「用户数据或线上请求会错」的回归，不要把实现细节、文案和源码字符串锁死。锁错了，以后每次改功能都要先改测试。

---

## 0. 一分钟规则（English）

- Test **product invariants** (data loss, wrong wire payload, stale compact/digest, migrations). Do not test prompt prose, UI copy, or Kotlin source text.
- Do **not** add `*SourceContractTest` that `File.readText()` and `contains("clip = false")`.
- Prefer **one table-driven test** per function over one `@Test` per boolean.
- Daily: `:app:compilePlayDebugKotlin` plus `--tests` on classes you touched. Full `:app:testPlayDebugUnitTest` before merge/release. After every app code change, also run `:app:assembleFdroidDebug` so `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` is ready to install.
- `JAVA_HOME` = Android Studio JBR. Run the project `gradlew` with no `--no-daemon` and no proxy JVM flags, so the build reuses Android Studio's warm daemon and `app/build`. Proxy is set once in the user `~/.gradle/gradle.properties` (port 7897), outside the repo. MockK DAO tests may fail on JDK 25; that is environment, not a product regression (see §5).
- Compilation is not enough for Compact, Room, or send-intercept behavior.

---

## 1. 什么时候必须写测试

只在不变量会让用户吃亏、或大模型下次极易写回旧行为时写：

| 值得测 | 例子 |
|--------|------|
| 压缩 / Digest / 格式版本 | `compactFormatStale`、digest 知识是否过期、组批边界 |
| 迁移与兼容 | Room migration、从用户 Default 剥旧政策段、旧 XML 用户消息包裹 |
| 发给模型的结构 | 前缀在用户提示之前、活跃记忆块不重复、Digest 作者不收人设政策 |
| 解析与投影 | 流式终止、citation、summary_batches / context_digest JSON |
| 持久化契约 | 导入导出不丢字段、en/zh 字符串 key 对齐（`SettingsResourceContractTest`） |
| 并发与身份 | Run/mailbox 过期结果拒绝、Stop 与落盘（已有 reducer 测试） |

## 2. 什么时候不要写测试

| 不要测 | 原因 |
|--------|------|
| 提示词 / 系统前缀里的某句中文或英文 | 文案一改就挂；锁「自定义提示前面有前缀、旧英文段会被剥掉」即可 |
| 读 `.kt` 断言还包含某函数名、某段排版 | 这是备忘录不是测试；本仓库已删除全部 `*SourceContractTest` |
| 每个 `if` 一个 `@Test` | 用表驱动；失败时把 case 名写进 assert 消息 |
| 为了「这次改动看起来完整」而补的 contains 清单 | 工具名列表会随 Default 模板变，不保护压缩机制 |
| 用 MockK 再包一层 DAO，只为证明调用了某方法 | JDK 25 + Byte Buddy 已经不稳定；优先测纯函数 |

UI 观感（间距、动画、Compose 结构）没有 instrumented 测试。不要用源码字符串合同冒充 UI 测试。真要测界面，用设备或后续的 Compose 测试，不要 `File.readText()`。

## 3. 日常怎么跑

`JAVA_HOME` 使用 Android Studio 自带 JBR，例如：

`C:\Program Files\Android\Android Studio\jbr`

用仓库里的 `gradlew`（Windows 上是 `gradlew.bat`）。命令行和 Android Studio 共用 `C:\Users\XiaoXie\.gradle` 里的依赖缓存，以及项目 `app\build` 里已经编过的结果。

命令里不要加 `--no-daemon`，也不要把代理写进 `JAVA_OPTS`、`GRADLE_OPTS` 或 `-Dhttp.proxyHost`。那会另起一个用完即关的进程，Android Studio 里已经热着的编译进程用不上，Gradle 安装包也可能再下一遍。

代理只配一次，写在用户目录的 `C:\Users\XiaoXie\.gradle\gradle.properties`（仓库外面，不要提交）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
systemProp.http.nonProxyHosts=localhost|127.0.0.1
```

这个文件改过之后，Android Studio 里如果还留着更早开的编译进程，先在 Android Studio 里编译一次，或关掉再打开，让它读到这份代理。

Flavor：日常编译和单测用 Play Debug。每次改完应用代码，都再编一次 fdroid 调试包，安装包放在 `app\build\outputs\apk\fdroid\debug\app-fdroid-debug.apk`，不用等用户再要。不要同一次命令里既打安装包又跑另一套 flavor 的测试。

| 时机 | 命令 |
|------|------|
| 改完 Kotlin，先看能不能编过 | `./gradlew :app:compilePlayDebugKotlin` |
| 只验证这次动过的纯逻辑 | `./gradlew :app:testPlayDebugUnitTest --tests com.newoether.agora.data.AgoraSystemPrefixTest`（可写多个 `--tests`） |
| 每次改完应用代码，打出可安装的包 | `./gradlew :app:assembleFdroidDebug` |
| 合并 / 发版 / 动了压缩或 Room | `./gradlew :app:testPlayDebugUnitTest` |
| 文档与字符串 | 另见 `documentation-maintenance.md`；`SettingsResourceContractTest` 要求 `values/` 与 `values-zh/` key 一致 |

不要把全量单测当成每次小改的默认步骤。编译已经挡住语法和大部分接线错误。

## 4. 怎么写（给大模型）

1. **先写不变量，再写断言。** 一句人话：「自定义提示前面必须有内置前缀；用户 Default 里不得再烘焙政策段。」然后只断言这两句。
2. **调用生产函数。** `applyAgoraSystemPrefix(...)`、`digestBackgroundStale(...)`，不要打开源文件做 `contains`。
3. **表驱动。** 同一函数的多种输入放在一个测试里循环，`assertEquals(caseName, expected, actual)`。
4. **失败要能读。** 不要五个测试都叫 `test1`…`test5`；case 名写清「promptRev 落后应变 stale」。
5. **迁移测旧输入。** 旧英文 `Context compression (Agora):` 块、旧 XML 包裹，用固定字符串当输入，不要复制整份当前 Default 正文当期望。
6. **不要为新提示词段落加 contains。** 改 `AgoraSystemPrefix` 文案时，只跑现有结构测试；不要新增「必须出现某某句」。
7. **禁止再新增 `*SourceContractTest`。** 也不要在别的测试里 `File(...).readText()` 去扫 `app/src/main`。
8. **`SettingsResourceContractTest` 保留：** 它检查 `values/` 与 `values-zh/` 的字符串 **key** 是否对齐，读的是 XML 资源，不是 Kotlin 源码。不要把它改成扫 `.kt`。

范例（结构，不是要求逐字抄）：

```kotlin
@Test
fun digestBackgroundStale_matchesRevAndPromptIdentityRules() {
    data class Case(val name: String, val stored: DigestBackgroundSnapshot, val current: DigestBackgroundSnapshot, val stale: Boolean)
    val cases = listOf(
        Case("am rev advanced", DigestBackgroundSnapshot(1, "p", 0), DigestBackgroundSnapshot(2, "p", 0), true),
        Case("revs match", DigestBackgroundSnapshot(4, "p", 2), DigestBackgroundSnapshot(4, "p", 2), false),
    )
    for (c in cases) {
        assertEquals(c.name, c.stale, digestBackgroundStale(c.stored, c.current))
    }
}
```

## 5. 已知环境问题（不是产品回归）

以下测试用 MockK 打 `ChatDao`，在 **JDK 25** 上可能因 Byte Buddy 不支持而失败；在 JDK 17/21 或 JBR 17/21 上预期通过：

- `ChatContextCompactDaoTest`
- `ChatDaoRunAdmissionTest`
- `ChatDaoRunRecoveryTest`

不要为了让 JDK 25 变绿而删掉这些测试的断言，也不要当成压缩功能坏了。可用 `net.bytebuddy.experimental=true` 作权宜。新测试尽量不要再 MockK 具体 DAO。

## 6. 和别的文档的关系

- 行为对不对：模块合同（`development/`）和 `development-docs/context-compact-*.md`。
- 用户手册：`docs/zh` / `docs/en`，流程见 `documentation-maintenance.md`。
- 测试政策：只此一页。改测试习惯时改本文，不要在每个功能文档里再写一套「必须写 12 个测试」。
