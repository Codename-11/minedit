# Devlog

## 2026-06-13

- Added canonical `/minedit ...` command namespace while keeping legacy top-level commands for compatibility.
- Fixed the grouped command tree to avoid server crashes from ambiguous Brigadier redirects.
- Added direct Codex app-server support with LAN auth instructions.
- Added Forge 1.20.1 support alongside the NeoForge root build.
- Added provider status/model-list abstractions for OpenRouter, Codex, Cursor, and Hermes.
- Added the first Minedit brand direction: selected footprint plus edit cursor.
- Split user-facing documentation into a VitePress app under `user-docs/`.
- Standardized release jar naming as `Minedit-NeoForge-<mc>-v<version>.jar` and `Minedit-Forge-<mc>-v<version>.jar`.

## Notes

- Product-facing name: `Minedit`.
- Mod id, resource namespace, and config names: `minedit`.
- Legacy/internal package naming still uses `aibuilder` in places. Avoid broad renames until there is a dedicated migration.
