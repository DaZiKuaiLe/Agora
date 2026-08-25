# 生成参数按模型（per-model）重构

状态：已实现（2026-08-22）。数据层、请求组装、上下文拆分、估算余量、
思考深度动态档位、按模型 UI、以及导入导出均已完成。旧版全局默认参数
仍保留为采样参数的只读兜底。

## 1. 问题

生成参数目前横跨两个正交维度存储——全局默认 + 会话覆盖——而不区分每个
参数的实际语义：

```kotlin
// GenerationRequestBuilder.buildEffectiveConversationSettings
temperature = overrides.temperature ?: settings.defaultTemperature.value
thinkingLevel = overrides.thinkingLevel ?: settings.thinkingLevel.value
```

每个参数都被压平成同一条「全局默认 → 会话覆盖」链。这在三方面是错的：

1. **采样参数是与模型绑定的。** `temperature`、`maxTokens`、`topP`、
   penalties 和思考深度，描述的是「某个具体模型如何生成」。它们应该
   跟随模型，而不是跟随会话或某个全局默认值。

2. **工具开关与采样参数被混在同一层级。** 代码执行、联网搜索、shell 等
   开关是「能力」（全局门 + 会话覆盖），却和 temperature、思考深度等
   采样参数挤在同一条「会话覆盖」链里，语义被混淆。

3. **「上下文窗口」是个误称。** 存储的值（`maxContextWindow`）实际上是
   「压缩触发阈值」（压缩在 `value × thresholdPercent` 时触发），而不是
   模型的真实上下文上限。模型的真实上限从未被存储或强制执行——超限时
   只会表现为 API 报错。

## 2. 目标

按参数的真实语义重新分类，让每个概念有唯一、明确的归属：

| 维度 | 归属 | 参数 |
|---|---|---|
| **按模型** | `Map<canonicalModelId, ModelGenerationSettings>` | 采样参数 + 思考 + 上下文 |
| **会话（工具开关）** | 全局门 + `ConversationToolState` 会话覆盖 | 能力开关 |
| **全局（单值）** | 全局 DataStore key | OpenAI service tier |

彻底移除采样参数那条多余的「全局默认 → 会话覆盖」链。

## 3. 参数分类

### 3.1 按模型 —— 采样 + 上下文（11 个字段）

| 字段 | 旧来源 | 默认值 |
|---|---|---|
| `temperature` | `defaultTemperature` | `null`（模型默认） |
| `maxTokens` | `defaultMaxTokens` | `null`（不限制） |
| `topP` | `defaultTopP` | `null` |
| `frequencyPenalty` | `defaultFrequencyPenalty` | `null` |
| `presencePenalty` | `defaultPresencePenalty` | `null` |
| `thinkingEnabled` | `thinkingEnabled` | `true` |
| `thinkingLevel` | `thinkingLevel` | 厂商默认（见 §5） |
| `thinkingBudgetEnabled` | `thinkingBudgetEnabled` | `false` |
| `thinkingBudgetTokens` | `thinkingBudgetTokens` | `4096` |
| `contextLimit` | *（新增）* | 用户填写的真实上限 |
| `compactThreshold` | `maxContextWindow` | 用户填写的压缩触发值 |

### 3.2 会话 —— 工具/能力开关（全局门 + 会话覆盖）

工具开关保留「全局门 → 会话覆盖」两级：全局开关关掉即对所有会话强制关闭，
会话内可再单独覆盖。这是运行时的临时切换，与所选模型无关，因此仍属于会话级。

- `codeExecutionEnabled`
- `googleSearchEnabled`
- `openAiWebSearchEnabled`
- `webSearchEnabled`
- `shellEnabled`

### 3.3 全局 —— 单值

- `openAiServiceTier`（+ `openAiServiceTierEnabled`）

### 3.4 保持不变（本来就正确）

- `providerBaseUrls`、`apiKeys`、`activeApiKeyIds`、`responsesApiEnabled` ——
  已经是按 provider 的。
- `contextWindow` —— 已退役，由 `contextLimit` + `compactThreshold` 取代。

## 4. 数据模型

```kotlin
@Serializable
data class ModelGenerationSettings(
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val thinkingEnabled: Boolean = true,
    val thinkingLevel: String = "",          // 空 = 厂商默认；否则为厂商真实档位
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val contextLimit: Int? = null,           // 模型真实上下文上限，用户填写
    val compactThreshold: Int? = null,       // 压缩触发值，用户填写
)
```

存储方式与 `modelAliases`（`SettingsModelPreferenceStore` 中的
`Map<String, String>`）对齐，以 `canonicalModelId(modelId)` 为 key。

## 5. 默认策略

- **`thinkingLevel`** → 为空时取厂商默认：
  - `deepseek` → `high`
  - `openai` / `open router` → `medium`
  - `google` / `gemini` → `medium`
  - 其他（anthropic / 未知）→ `medium`
- **其余采样参数** → 采用 §3.1 中列出的写死默认值。

## 6. 上下文窗口拆分

用两个独立值取代单一的 `maxContextWindow`（「预算」）：

- **`contextLimit`** —— 模型真实上下文上限，用户填写（不内置模型→上限表；
  维护成本过高）。
- **`compactThreshold`** —— 触发压缩的绝对 token 数。

规则：

- 写入和 UI 层校验 `compactThreshold < contextLimit`。
- **彻底删除 `contextCompactThresholdPercent`** —— 它是个多余的 10% 余量
  系数，与进度条报警余量重复。
- 进度条分母改为 `contextLimit`；报警（变色 → 出错）在 `contextLimit` 的
  90% 处写死。
- 当 `estimatedTokenCount >= compactThreshold` 时触发压缩。

## 7. Token 估算冗余

估算器在 `applySafetyMargin` 里已经加了 `×1.1` 的安全余量，它与 90% 报警
阈值叠加后，实际报警发生在真实用量的 `0.9 / 1.1 ≈ 81.8%` 处。冗余必须只
存在于一处。

- 将 `SAFETY_NUMERATOR / SAFETY_DENOMINATOR` 设为 `1 / 1`（无余量）。
- 保留 90% 进度条报警作为唯一的冗余来源。
- 保留用户可控的 `compactThreshold` 作为压缩的冗余来源。

## 8. 迁移与导入导出

- **全局思考 key 已彻底删除**：`thinkingEnabled` / `thinkingLevel` /
  `thinkingBudgetEnabled` / `thinkingBudgetTokens` 不再有全局 key，思考
  完全按模型存储（`thinkingLevel` 为空时取厂商默认）。
- **旧版全局采样默认保留为只读兜底**：`defaultTemperature`、`defaultMaxTokens`、
  `defaultTopP`、`defaultFrequencyPenalty`、`defaultPresencePenalty` 不迁移、
  不清除，仅作为某模型未显式配置时的回退值。
- **`PortableSettingsArchive` / `ExportExtraSettings`**：读写按模型
  `modelGenerationSettings` map；旧的全局 thinking key 不再写出（导入时忽略）。
  会话序列化只保留工具开关（`ConversationToolState`）。

## 9. 实施顺序

1. 数据层：`ModelGenerationSettings` + 按模型 map 存储。
2. 请求组装：`GenerationRequestBuilder` + 瘦身 `GenerationConfig` /
   `ConversationSettings`。
3. 思考深度 UI：`ChatBottomBarOverlayHost` 中的厂商档位滑条。
4. 上下文拆分：`contextLimit` + `compactThreshold`，删除百分比、估算器
   余量、进度条。
5. UI 表面：`AdvancedSettingsDialog`、`SettingsGenerationPage`、
   `SettingsContextPage`。
6. 迁移 + 导入导出 + 测试。
