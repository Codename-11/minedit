# Minedit

Minedit is an experimental Minecraft mod for building and editing structures with AI models through OpenRouter, Codex app-server, a local bridge for Codex or Cursor, or a Hermes runs endpoint.

Select a footprint with a stick, describe what you want, and Minedit asks a model to generate compact builder code that places blocks in the selected area. It can also edit existing builds with compact line-aware patches, generate builds in focused stages, and run agent modes.

This fork is maintained as a multi-loader, multi-provider build. The current focus is preserving the existing NeoForge behavior while adding Forge 1.20.1 support and a cleaner provider path for local and remote agents.

## Status and Risk

Minedit is a work in progress. Expect things to break.

This mod sends prompts to the provider you configure. OpenRouter requests use the API key you configure. Codex requests use your Codex/OpenAI login and may consume Codex, ChatGPT, or OpenAI plan limits. Cursor local bridge requests use your local Cursor login or API key and may consume Cursor plan limits. Hermes requests use the Hermes endpoint and token you configure and may consume whatever account or runtime that endpoint is backed by. Depending on your provider, model, account, and usage, requests may cost money or consume plan limits. You are responsible for all usage and charges caused by your configured provider. Use this mod at your own risk. The author is not responsible for unexpected costs, world changes, broken builds, broken saves, or other side effects.

Your OpenRouter API key, saved Codex app-server token, and saved Hermes token are stored in plaintext in your Minecraft game directory at `config/minedit.properties`. They are not stored per-world. Do not share this file, screenshots of it, modpacks containing it, or support logs that include it.

Back up worlds before testing large builds, staged builds, agent builds, or edits.

## Supported Loaders

| Loader | Minecraft | Loader version | Development Java | Build output |
| --- | --- | --- | --- | --- |
| NeoForge | `26.1.2` | NeoForge `26.1.2.73` | Java 25 | `build/libs/minedit-<version>.jar` |
| Forge | `1.20.1` | Forge `47.4.5` or compatible `47.x` | Java 17 | `forge1201/build/libs/minedit-forge-1.20.1-<version>.jar` |

Forge support is currently scoped to Minecraft `1.20.1` and Forge `47.4.5`. Additional Minecraft versions should be added deliberately when an API or loader break requires a separate compatibility target.

Minedit is required on the server for commands, AI calls, block placement, and rollback. Installing it on clients is optional and only adds client-side selection particles.

## Providers

| Provider | Configure | Transport | Supported modes | Notes |
| --- | --- | --- | --- | --- |
| OpenRouter | `/minedit provider openrouter`, `/minedit apikey <key>` | OpenAI-compatible chat completions | build, staged build, edit, quick edit, chat | Default provider. Supports streaming progress and usage lookup. |
| Codex | `/minedit provider codex`, `/minedit codex url <url>`, `/minedit codex token <token>` | Direct `codex app-server` WebSocket or optional Minedit bridge | build, staged build, edit, quick edit, agent build, chat | `/minedit provider codex-local` remains accepted. Step-by-step uses one direct batch without bridge tools. |
| Hermes | `/minedit provider hermes`, `/minedit hermes url <url>`, `/minedit hermes token <token>` | Direct Hermes `/v1/runs` and SSE events | build, staged build, edit, quick edit, agent build, chat | Approval requests are shown as progress messages; Minedit does not approve actions automatically. |
| Cursor | `/minedit provider cursor`, `/minedit codex url <url>` | Local Minedit bridge to Cursor CLI | build, staged build, edit, quick edit, agent build, step-by-step agent build, chat | Cursor models come from `/minedit model list cursor`. |

Provider requirements:

- OpenRouter API key for OpenRouter mode
- Codex CLI for direct Codex app-server mode
- Node.js 18+ for optional local bridge mode
- Cursor CLI for local Cursor bridge mode
- Hermes `/v1/runs` endpoint for Hermes mode

## Installation

You can either download a prebuilt jar from the GitHub Releases page or build it yourself.

### Download a Release

1. Download the latest jar from the [Minedit releases page](https://github.com/Angais/minedit/releases).
2. Copy the jar into your Minecraft `mods` folder.
3. Start the matching NeoForge or Forge profile.

### Build from Source

NeoForge:

```sh
./gradlew jar
```

Copy the jar from `build/libs/` into your NeoForge `mods` folder.

Forge 1.20.1:

```sh
./gradlew :forge1201:jar
```

Copy the jar from `forge1201/build/libs/` into your Forge 1.20.1 `mods` folder.

## Quick Start

Minedit uses OpenRouter by default.

```mcfunction
/minedit apikey <your-openrouter-key>
/minedit model openai/gpt-5.5
```

Select two X/Z footprint corners with a stick:

- Left click a block to set `pos1`.
- Right click a block to set `pos2`.

Then run:

```mcfunction
/minedit build a detailed medieval blacksmith
```

Minedit uses the selected X/Z area as the footprint. Height is not capped by the selection.

Legacy top-level commands such as `/build`, `/edit`, `/model`, and `/status` still work. New docs prefer the grouped `/minedit ...` commands.

Install Minedit on the client to use `/minedit gui`, a small in-game control screen for status, model list, streaming, selection-tool, clear-selection, and stop controls. `/mineditgui` is also available as a client-only shortcut.

## Build Modes

### Normal Build

One model call generates the whole build:

```mcfunction
/minedit build a cute house
```

Build mode clears existing non-air blocks in the selected footprint before placing the generated structure.

### Staged Build

Several focused model calls build the structure in phases:

```mcfunction
/minedit build stages a detailed wizard tower
```

The staged builder currently runs these stages:

1. Foundation and frame
2. Walls, openings, doors, and windows
3. Roof, ceilings, stairs, and vertical access
4. Interior lighting and furniture
5. Exterior detail and landscaping
6. Final corrections and polish

Each stage receives the previous stage code as context and should only output incremental work for the current stage. This usually costs more than `/build`, but it gives the model more focus per phase.

### Agent Build

Agent mode works directly through Codex app-server or Hermes, or through the local bridge for Codex/Cursor:

```mcfunction
/minedit provider codex
# or
/minedit provider cursor
# or
/minedit provider hermes
/minedit build agent <prompt>
/minedit build agent step-by-step <prompt>
```

`/minedit build agent <prompt>` asks the configured agent provider to draft, preview, and revise before Minecraft places the final build.

`/minedit build agent step-by-step <prompt>` places the build in visible steps when the selected provider can emit batches. Codex direct app-server mode emits one final batch. Codex bridge mode uses Minedit dynamic tools such as `place_step`, `render_preview`, `inspect_status`, and `finish_build`. Cursor uses the bridge's phased step generator and emits placement batches as each phase completes.

## Editing

Use `/edit` to modify the selected area based on its current blocks:

```mcfunction
/minedit edit make the roof steeper and add windows
```

Use quick edit for small targeted patches:

```mcfunction
/minedit edit quick remove the flower and change the oak planks to spruce
```

Normal edit and quick edit use a compact line-aware representation of the current build, so models can emit small patches like `api.replaceLine(...)`, `api.clearLine(...)`, `api.set(...)`, or `api.fill(...)` instead of rebuilding unchanged geometry.

Set quick edit reasoning effort:

```mcfunction
/minedit edit set quickeffort low
```

## Chat

Use chat mode when you want to ask the configured provider a question in-game without always starting a build:

```mcfunction
/minedit chat what would fit in this footprint?
```

If a selection exists and the message clearly asks Minedit to build, create, place, or construct something, the agent may return build code and Minedit will queue it in the selected area. Plain questions return chat text only.

## Codex

Minedit can connect directly to a Codex app-server WebSocket. Start Codex on the machine that should run the app-server:

```sh
codex app-server --listen ws://127.0.0.1:4500
```

Then in Minecraft:

```mcfunction
/minedit provider codex
/minedit codex url ws://127.0.0.1:4500
/minedit codex status
/minedit model gpt-5.5
```

For another LAN, VPN, or SSH-tunneled host, `0.0.0.0` requires WebSocket auth. On the Codex host:

```sh
mkdir -p ~/.codex/minedit
openssl rand -base64 32 > ~/.codex/minedit/app-server.token
chmod 600 ~/.codex/minedit/app-server.token

codex app-server \
  --listen ws://0.0.0.0:4500 \
  --ws-auth capability-token \
  --ws-token-file ~/.codex/minedit/app-server.token
```

Copy the token with:

```sh
cat ~/.codex/minedit/app-server.token
```

Then configure Minecraft:

```mcfunction
/minedit provider codex
/minedit codex url ws://codex-host:4500
/minedit codex token <token>
/minedit codex status
```

Use `ws://` only for localhost, VPN, or SSH-tunneled connections. For shared or remote networks, put the app-server behind TLS and auth, then use `wss://`.

Codex model ids usually do not use the OpenRouter `openai/` prefix. Minedit strips `openai/` automatically, so `openai/gpt-5.5` becomes `gpt-5.5`, but setting `/model gpt-5.5` is clearer when using Codex.

## Local Bridge

The local bridge is optional. Use it for Cursor, or when you want the original Minedit bridge behavior for Codex.

Requirements:

- Node.js 18+
- Codex CLI installed and logged in for `/minedit provider codex` or `/minedit provider codex-local`
- Cursor CLI installed and logged in for `/minedit provider cursor`
- This repository or source zip available locally, because the bridge code lives in `bridge/`

Log in once if needed:

```sh
codex login
agent login
```

Install bridge dependencies once:

```sh
npm --prefix bridge install
```

Default Codex mode starts `codex app-server` for each bridge request:

```sh
npm --prefix bridge start
```

The bridge listens on:

```text
http://127.0.0.1:8765
```

Then in Minecraft:

```mcfunction
/minedit provider codex
/minedit codex url http://127.0.0.1:8765
/minedit codex status
/minedit model gpt-5.5
```

For Cursor:

```mcfunction
/minedit provider cursor
/minedit codex url http://127.0.0.1:8765
/minedit model list cursor
/minedit model auto
```

Cursor uses `agent -p --mode=ask` for normal build/edit/staged requests. Cursor model ids are the ids returned by `/minedit model list cursor`, such as `auto` or account-specific ids like `gpt-5.5-medium`.

## Hermes

Hermes mode sends requests directly to a Hermes runs endpoint. The default URL is:

```text
http://127.0.0.1:8642/v1
```

Configure Hermes in Minecraft:

```mcfunction
/minedit provider hermes
/minedit hermes url http://127.0.0.1:8642/v1
/minedit hermes token <token>
/minedit model gpt-5.5
```

If no Hermes token is saved, Minedit will use the `HERMES_GATEWAY_TOKEN` environment variable when it is available. Hermes approval requests are surfaced as progress messages, but Minedit does not grant approvals automatically.

## Settings Commands

```mcfunction
/minedit provider openrouter
/minedit provider codex
/minedit provider codex-local
/minedit provider hermes
/minedit provider cursor
/minedit apikey <openrouter-key>
/minedit codex url ws://127.0.0.1:4500
/minedit codex token <token>
/minedit codex status
/minedit hermes url http://127.0.0.1:8642/v1
/minedit hermes token <token>
/minedit model list
/minedit model list cursor
/minedit model <model-id>
/minedit build export <prompt>
/minedit build import
/minedit chat <message>
/minedit selection tool on
/minedit selection tool off
/minedit selection clear
/minedit effort none
/minedit effort minimal
/minedit effort low
/minedit effort medium
/minedit effort high
/minedit effort xhigh
/minedit effort max
/minedit streaming enabled
/minedit streaming disabled
/minedit stop
/minedit status
/minedit usage <openrouter-generation-id>
/minedit gui
```

Defaults:

```text
provider: openrouter
model: openai/gpt-5.5
normal effort: medium
quick edit effort: low
OpenRouter streaming: enabled
```

`/minedit model list` checks the current provider by default. OpenRouter, Codex, and Cursor expose model ids; Hermes currently reports status only because the configured `/v1/runs` endpoint does not expose model discovery.

`/minedit streaming enabled` streams OpenRouter responses and shows progress/reasoning summaries when the provider sends them. `/minedit streaming disabled` waits for the full response before showing usage and queueing placement.

`/minedit selection tool off` disables the stick selector for your player so normal stick clicks work again. `/minedit selection tool on` re-enables left-click `pos1` and right-click `pos2` selection.

`/minedit stop` requests cancellation for your current Minedit generation and removes your queued block placement jobs. It can interrupt OpenRouter streams and queued placement immediately. Direct Codex jobs are stopped by closing the app-server WebSocket, bridge-backed Codex/Cursor agent jobs are cancelled through the local bridge when possible, and Hermes runs are stopped through the configured runs endpoint when possible.

`/minedit status` shows the current provider, selected model, normal reasoning effort, quick edit reasoning effort, streaming setting, key/bridge/Hermes configuration, selection-tool state, current selection, active AI generations, and queued block placement jobs.

Settings are saved in `config/minedit.properties`. The OpenRouter API key, Codex app-server token, and Hermes token in that file are plaintext and belong to the whole Minecraft game directory/profile, not a single world. The Codex URL, Hermes URL, and provider selection are also stored there. If you used an older build, Minedit will try to read the legacy `config/aibuilder.properties` file.

## Manual Export and Import

To use a model outside Minedit without making an in-game API call:

```mcfunction
/minedit build export <prompt>
```

This writes the exact build prompt to `config/minedit-debug/export-prompt.txt` and creates `config/minedit-debug/import-build.js` if needed. Send the exported prompt to a model yourself, then paste either the full model response or just the returned `function build(api) { ... }` code into `import-build.js`.

Then select the same footprint in Minecraft and run:

```mcfunction
/minedit build import
```

Minedit parses the imported response through the same build-code parser used for API responses, clears the selected footprint like normal build mode, and queues the resulting block operations. For very small snippets, `/minedit build import <code>` also works, but the file workflow is safer for real generated builds.

## OpenRouter Usage and Cost

After OpenRouter builds/edits, Minedit prints usage data when available:

- input tokens
- reasoning tokens
- output tokens
- estimated cost or BYOK upstream inference cost
- model
- finish reason
- generation id

If OpenRouter's final generation metadata already includes cost, the first usage line includes it. Otherwise Minedit keeps checking in the background and sends a separate cost line when it becomes available. You can also run `/minedit usage <generation-id>` to manually fetch the latest generation usage.

Minedit only displays usage fields. It does not print API keys.

OpenRouter streaming mode shows progress and provider-supplied reasoning summaries when available. It does not display raw hidden chain-of-thought.

## Reset Commands

Undo the last generated build/edit for your player:

```mcfunction
/minedit reset build
```

Clear the current selection:

```mcfunction
/minedit selection clear
```

## Examples

Model output depends on the model, effort setting, selected footprint, and surrounding world state. These examples show one generated build and three follow-up edits.

Build generation:

```mcfunction
/build a cute house
```

![Generated cute house](docs/examples/01-build-cute-house.png)

Quick edit:

```mcfunction
/edit quick make the walls red please
```

![Quick edit changing the walls red](docs/examples/02-quick-edit-red-walls.png)

Quick edit:

```mcfunction
/edit quick can you please change the wood for stone? a cool one
```

![Quick edit changing wood details to stone](docs/examples/03-quick-edit-stone-wood.png)

Quick edit:

```mcfunction
/edit quick don't really like those plants outside, can you remove them?
```

![Quick edit removing outside plants](docs/examples/04-quick-edit-remove-plants.png)

## Debug Files

When a model response fails, Minedit writes debug files to:

```text
config/minedit-debug/
```

Useful files:

- `last-prompt.txt`
- `last-response.txt`
- `last-build.js`

These files may contain your prompts and generated code. They should not contain API keys, but review them before sharing.

## Notes on Generated Builds

Minedit prompts models to avoid common Minecraft placement problems such as unsupported plants, inverted roofs, stair orientation mistakes, unreachable stairs or ladders, missing landings, roof gaps, trapped doors, missing lintels/header blocks above doors, isolated pane/fence/wall slivers, blocked paths, cramped rooms, low ceilings, short support pillars, blocked window views, empty rooms, under-decorated upper floors, unlit interiors, and fragile blocks without support. It also prompts models to treat non-enterable builds such as statues, monuments, fountains, terrain features, vehicles, and decorative objects differently from houses. It also checks Minecraft block survival rules before placing blocks, so unsupported fragile blocks may be skipped.

Model output is still imperfect. Use `/minedit reset build` and world backups while testing.

## Architecture Notes

- Shared mod code lives in `src/main/java`.
- The NeoForge build is the root Gradle project.
- The Forge 1.20.1 build lives in `forge1201/` and generates Forge-compatible sources from the shared Java sources during Gradle builds.
- Loader differences are intentionally kept narrow: Forge-specific metadata, client bootstrap, event/import rewrites, and small Minecraft 1.20.1 API shims live in the Forge subproject.
- Provider selection flows through `AiProvider`, `AiRequestOptions`, and provider-specific clients.
- Generated builds are constrained to the selected footprint/build zone. `/minedit reset build` restores the pre-edit snapshot for the last generated build/edit when available.

When adding another Minecraft version, prefer a small loader/version subproject first. Move code into a deeper common/platform abstraction only when compatibility rewrites become hard to reason about.

## Credits and Third-Party Technology

- Built with the NeoForge MDK template. The template files are MIT licensed by the NeoForged project; see `TEMPLATE_LICENSE.txt`.
- Uses NeoForge and Minecraft Forge for mod loading and APIs.
- Bundles a relocated private copy of Mozilla Rhino `1.8.0` as the JavaScript runtime. Rhino is licensed under the Mozilla Public License 2.0: https://www.mozilla.org/MPL/2.0/
- Uses OpenRouter's OpenAI-compatible chat completions API.
- Optionally uses the OpenAI Codex app-server through the local `bridge/` helper.
- Optionally uses Cursor CLI through the local `bridge/` helper.
- Optionally uses Hermes `/v1/runs` endpoints directly.

## License

Minedit is currently published as All Rights Reserved unless a separate license is added later. NeoForge MDK template files keep their original MIT license, documented in `TEMPLATE_LICENSE.txt`.
