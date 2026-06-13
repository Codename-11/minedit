# Minedit

<p align="center">
  <img src="assets/minedit-logo.svg" alt="Minedit logo" width="360">
</p>

Minedit is an experimental Minecraft mod for building and editing structures with AI. Select a footprint with a stick, describe what you want, and Minedit asks the configured provider to generate compact builder code for that selected area.

Minedit supports OpenRouter, direct Codex app-server, Cursor through the local bridge, and Hermes runs endpoints.

## Status

Minedit is a work in progress. Back up worlds before testing large builds, staged builds, agent builds, or edits.

Provider requests may cost money or consume plan limits. OpenRouter API keys, saved Codex tokens, and saved Hermes tokens are stored in plaintext at `config/minedit.properties`.

## Supported Builds

| Loader | Minecraft | Loader version | Development Java | Release jar |
| --- | --- | --- | --- | --- |
| NeoForge | `26.1.2` | NeoForge `26.1.2.73` | Java 25 | `Minedit-NeoForge-26.1.2-v<version>.jar` |
| Forge | `1.20.1` | Forge `47.4.5` or compatible `47.x` | Java 17 | `Minedit-Forge-1.20.1-v<version>.jar` |

Minedit is required on the server for commands, AI calls, block placement, and rollback. Installing it on clients is optional and adds selection particles plus `/minedit gui`.

## Quick Start

OpenRouter is the default provider:

```text
/minedit apikey <your-openrouter-key>
/minedit model openai/gpt-5.5
```

Select two X/Z footprint corners with a stick:

- Left click a block to set `pos1`.
- Right click a block to set `pos2`.

Then build:

```text
/minedit build a detailed medieval blacksmith
```

Legacy top-level commands such as `/build`, `/edit`, `/model`, and `/status` still work for compatibility. New docs use `/minedit ...` as canonical.

## Build From Source

NeoForge:

```sh
./gradlew jar
```

Forge 1.20.1:

```sh
./gradlew :forge1201:jar
```

Both public jars:

```sh
./gradlew jar :forge1201:jar
```

User docs:

```sh
npm ci
npm run docs:dev
```

## Documentation

- [User guide](user-docs/index.md)
- [Command reference](user-docs/commands.md)
- [Provider setup](user-docs/providers.md)
- [Development notes](docs/README.md)
- [Release process](RELEASE.md)
- [Changelog](CHANGELOG.md)

## Project Layout

- `src/main/java`: shared NeoForge source and most common code
- `forge1201/`: Forge 1.20.1 compatibility subproject
- `bridge/`: optional local bridge for Codex and Cursor
- `user-docs/`: VitePress user guide
- `docs/`: maintainer and design notes
- `assets/`: brand SVGs

## Credits

- Built with the NeoForge MDK template.
- Uses NeoForge and Minecraft Forge.
- Bundles a relocated private copy of Mozilla Rhino `1.8.0`.
- Uses OpenRouter, optional Codex app-server, optional Cursor CLI, and optional Hermes `/v1/runs`.

## License

Minedit is currently published as All Rights Reserved unless a separate license is added later. NeoForge MDK template files keep their original MIT license, documented in `TEMPLATE_LICENSE.txt`.
