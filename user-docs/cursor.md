# Cursor

Cursor support uses the local Minedit bridge.

## Requirements

- Node.js 18+
- Cursor CLI installed and logged in
- This repository or source zip available locally because the bridge code lives in `bridge/`

## Start The Bridge

Install dependencies once:

```sh
npm --prefix bridge install
```

Start the bridge:

```sh
npm --prefix bridge start
```

The bridge listens on:

```text
http://127.0.0.1:8765
```

Configure Minecraft:

```text
/minedit provider cursor
/minedit codex url http://127.0.0.1:8765
/minedit model list cursor
/minedit model auto
```

Cursor uses `agent -p --mode=ask` for normal build, edit, staged, and agent requests. Model ids are the ids returned by `/minedit model list cursor`.
