# Agentic Tools

Agora can let a model call tools across multiple generation passes.

## Available capabilities

Depending on settings, build, and provider support, tools include:

- web search
- image generation
- past-conversation search
- MCP servers
- Tasks and Loops automation
- SSH and local commands, process-scoped `start_*_process` tools, SFTP read/write/edit, one-level `list_*_files`, host-side `glob_*` / `grep_*`, `read_image`, and `open_file`. Ask mode (composer toggle, on by default) keeps only read/search tools.
- the F-Droid Alpine sandbox

## Permissions and defaults

Web search, past-conversation access, and the global shell permission are enabled by default. Active memory is background text in the system prompt, not a model-callable tool; clearing it in Settings → Memory stops sending it. Shell calls still require a configured device, and its confirmation policy remains authoritative. Automation tools are disabled by default. MCP availability is controlled per server and per tool.

Tool calls and results become part of the conversation protocol and can be sent to the selected model on subsequent passes. External tools receive the arguments needed for their call. Review each server and permission before enabling it.

Image-generation and embedding credentials come from their selected providers; they are not separate universal tool secrets. See [MCP](mcp.md), [Automation](automation.md), [Shell](shell.md), and [Privacy & Security](privacy.md).
