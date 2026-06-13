# Local Bridge

The bridge is optional for Codex and required for Cursor.

## Install

```sh
npm --prefix bridge install
```

## Start

```sh
npm --prefix bridge start
```

Default URL:

```text
http://127.0.0.1:8765
```

## Minecraft Setup

Codex through bridge:

```text
/minedit provider codex
/minedit codex url http://127.0.0.1:8765
```

Cursor:

```text
/minedit provider cursor
/minedit codex url http://127.0.0.1:8765
/minedit model list cursor
```

Direct Codex app-server no longer requires the bridge. Use `ws://` or `wss://` in `/minedit codex url`.
