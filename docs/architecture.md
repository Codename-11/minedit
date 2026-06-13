# Architecture

Minedit is a multi-loader Minecraft mod with a NeoForge root build and a Forge 1.20.1 compatibility subproject.

## Source Layout

- `src/main/java`: shared source used by the NeoForge build.
- `forge1201/`: Forge 1.20.1 compatibility build.
- `forge1201/build.gradle`: generates Forge-compatible sources from the shared Java source tree.
- `bridge/`: optional local HTTP bridge used by Cursor and legacy Codex bridge workflows.

## Runtime Flow

1. Player selects a footprint with the stick tool.
2. Player runs a `/minedit ...` command.
3. `AiBuilderCommands` builds `AiRequestOptions` from saved config.
4. `BuildJobService` asks the selected provider for a response.
5. `ResponseParser` extracts `function build(api)`.
6. `JsBuildRunner` turns builder code into operations.
7. `BuildQueue` places blocks server-side and supports cancellation.

## Providers

- OpenRouter uses an OpenAI-compatible chat API.
- Codex supports direct `codex app-server` WebSocket and optional bridge mode.
- Cursor uses the local bridge.
- Hermes uses direct `/v1/runs` plus SSE progress.

Provider status and model discovery use shared `ProviderStatus` and `ProviderModel` records where the upstream provider exposes enough metadata.
