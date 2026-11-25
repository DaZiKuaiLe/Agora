# 上下文压缩：一条概要 + 尾部原文

Status: **现行方案**，2026-09-07。  
给另一端客户端的机制说明：[`context-compact-mechanism.md`](context-compact-mechanism.md)。详情面板：[`context-detail-panel.md`](context-detail-panel.md)。  
旧文档仅作历史：[`context-compact-digest.md`](context-compact-digest.md)、[`context-compact-time-bands.md`](context-compact-time-bands.md)、[`context-compact-turn-archive.md`](context-compact-turn-archive.md)、[`context-compact-requirements.md`](context-compact-requirements.md)。

## 工作模型看见的

系统前缀 + 人设 + 活跃记忆 + **一条**纯正文驻留（若已压过；不是 JSON；多章上线时加整合段头）+ 最近 B 轮原文 + 本轮 live。Room / UI 仍是全文。无锁定。

## 折叠

达阈（量的是尚未折进驻留的原文，不是投影后总占用）：

`新章 = compress(刚滑出 B 的未覆盖已结束轮原文)`  
`驻留 = 旧章… + 新章`

不要把旧驻留再送进压缩模型。压缩输入用工具全文，不能在 `detail_ref` 上总结。活跃记忆 / 人设不写进驻留。半轮 / live 不进驻留。交互发送达阈先弹窗（压缩再发 / 本次不压仍发）。无预压写中间稿。发送热路径信增量账本，不以全量重估当正确性检查。

文笔：个人、亲友、情感、经历、独创结论近无损；通识可丢；工程只留确认事实与现行结论。

全量优化（手动「上下文优化」，或 `compactFormatRev` 落后时发送前必须确认、可暂缓不发）：按达阈节奏从窗外原文重切片（不拿旧驻留当输入，也不先拆完整段工具 JSON），成功后再给留下的原文做去细节化，并 bump 会话 `compactFormatRev`。机制变更时 `CompactFormat.CURRENT_REV` 加一。任务路径静默全量，失败则本轮不发。

## 取回

对话原文只在 Room。`search_current_conversation` 对本会话原文做关键词搜索。`query_detail_store` 只服务工具载荷。
