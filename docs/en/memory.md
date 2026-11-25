# Memory & Cache

Agora injects **active memory** into every conversation's system prompt (`<active_memory_context>`) as user-maintained background (glossary, identity, stable preferences). It is read-only during a chat: the model cannot edit it. You change it in **Settings → Memory**. Clearing that text stops sending it. Custom prompts without a memory variable still receive the block at the end of the system prompt.

Conversation-history access is configured under **Settings → Conversation Search**, not on the memory page.

If a conversation already has a **conversation summary** (from context compact) and you change active memory or the bound default prompt, Agora asks you to update that summary before the next send. Chats without a summary pick up the new background on the next turn without compacting.

Memory data is stored locally until you delete it or include it in a request. When an enabled model uses active memory, that content can be sent to the model provider. Clearing a cache is different from editing active memory; review the confirmation text before acting.

See [Conversation Search](search.md), [Agentic Tools](tools.md), and [Privacy & Security](privacy.md).
