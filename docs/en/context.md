# Context

Context settings control how much conversation history is available to each generation and what Agora does when that history approaches the configured token budget.

## Context Window

**Default Context Window** is a token budget, not a message count. Available presets range from **4K** to **1M** tokens; the default is **32K**.

The budget includes the provider-visible request: system instructions, enabled tool definitions, attachment text or stored image transcription, and the selected conversation branch. Agora estimates those costs locally, then keeps the newest complete messages that fit. A complete tool-call round is never split.

The context indicator in the chat bottom bar shows estimated usage. The estimate can differ from the provider's final token accounting because tokenizers and provider-side formatting vary.

After opening a conversation, **View context** in the top-bar menu lists the system prompt and messages that the next send would give the model (composer draft excluded). It is a read-only audit and does not rewrite the transcript.

A conversation can override the default context window from its advanced settings.

## Visualize Context Roll-Out

Enable **Visualize Context Roll-Out** to dim messages that are outside the current provider-visible window. The messages remain in the conversation and can become visible to the model again after switching branches or increasing the budget.

## Context Compact

Older finished turns fold into **one plain-text residency** in the provider request. The newest turns stay verbatim. Original messages are not deleted.

- **Automatic compact** asks before send when uncompressed originals cross the per-model threshold. You can compact then send, or send this time without compacting.
- **Compact model** chooses a dedicated enabled model. Leave it on the current model to follow the conversation.
- **Recent turns to keep** (default 8) is the verbatim tail. Everything older than that, once compacted, is represented by the residency.

Each later compact folds only the newly aged-out originals and appends that as another residency chapter; earlier chapters are not sent back through the compact model. Open the context indicator and choose **Optimize all context** to de-detail long tool results and rewrite every chapter from originals, replaying the same threshold cadence. After a compact-mechanism change, the next send asks you to finish that optimize first (you can postpone; sending stays blocked).

Use **search this conversation** when a detail is missing from the residency. `query_detail_store` is only for archived tool payloads.

!!! tip
    Use a smaller budget to reduce request size, or use compact when a long conversation should not send every old turn verbatim.

!!! note
    The residency is lossy. Check the original transcript in the chat UI for facts you cannot afford to miss.

