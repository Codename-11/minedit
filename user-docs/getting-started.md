# Getting Started

## Install

Download the jar for your loader and Minecraft version:

| Loader | Minecraft | Jar pattern |
| --- | --- | --- |
| NeoForge | `26.1.2` | `Minedit-NeoForge-26.1.2-v<version>.jar` |
| Forge | `1.20.1` | `Minedit-Forge-1.20.1-v<version>.jar` |

Copy the jar into the matching `mods` folder and start the profile. Minedit must be installed on the server. Client install is optional but recommended for the GUI and selection particles.

## Configure A Provider

OpenRouter is the default provider:

```text
/minedit apikey <your-openrouter-key>
/minedit model openai/gpt-5.5
```

For Codex, Cursor, or Hermes, see [Providers](./providers.md).

## Select A Footprint

Hold a stick:

- Left click a block to set `pos1`.
- Right click a block to set `pos2`.

Minedit uses the selected X/Z area as the footprint. Height is not capped by the selection.

## Build

```text
/minedit build a detailed medieval blacksmith
```

Build mode clears existing non-air blocks in the selected footprint before placing the generated structure.

## Control Screen

With the client mod installed:

```text
/minedit gui
```

The GUI gives quick access to status, model list, streaming toggle, selection-tool toggle, clear selection, and stop.

## Legacy Commands

Legacy top-level commands such as `/build`, `/edit`, `/model`, and `/status` still work for compatibility. New docs use `/minedit ...` as canonical.
