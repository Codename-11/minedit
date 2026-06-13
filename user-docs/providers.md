# Providers

| Provider | Configure | Transport | Modes |
| --- | --- | --- | --- |
| OpenRouter | `/minedit provider openrouter`, `/minedit apikey <key>` | OpenAI-compatible chat completions | build, staged build, edit, quick edit, chat |
| Codex | `/minedit provider codex`, `/minedit codex url <url>`, `/minedit codex token <token>` | Direct `codex app-server` WebSocket or optional bridge | build, staged build, edit, quick edit, agent build, chat |
| Cursor | `/minedit provider cursor`, `/minedit codex url <url>` | Local bridge to Cursor CLI | build, staged build, edit, quick edit, agent build, step-by-step agent build, chat |
| Hermes | `/minedit provider hermes`, `/minedit hermes url <url>`, `/minedit hermes token <token>` | Direct Hermes `/v1/runs` and SSE events | build, staged build, edit, quick edit, agent build, chat |

## Model Discovery

```text
/minedit model list
```

OpenRouter, Codex, and Cursor expose model ids. Hermes currently reports status only because the configured `/v1/runs` endpoint does not expose model discovery.

## Streaming

```text
/minedit streaming enabled
/minedit streaming disabled
```

Streaming applies to OpenRouter requests. Other providers may emit progress through their own event streams or bridge responses.

## Provider Pages

- [Codex](./codex.md)
- [Cursor](./cursor.md)
- [Hermes](./hermes.md)
