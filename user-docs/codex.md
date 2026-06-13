# Codex

Minedit can connect directly to a Codex app-server WebSocket.

## Localhost

Start Codex on the machine that should run app-server:

```sh
codex app-server --listen ws://127.0.0.1:4500
```

Then in Minecraft:

```text
/minedit provider codex
/minedit codex url ws://127.0.0.1:4500
/minedit codex status
/minedit model gpt-5.5
```

## LAN Host

`0.0.0.0` requires WebSocket auth. On the Codex host:

```sh
mkdir -p ~/.codex/minedit
openssl rand -base64 32 > ~/.codex/minedit/app-server.token
chmod 600 ~/.codex/minedit/app-server.token

codex app-server \
  --listen ws://0.0.0.0:4500 \
  --ws-auth capability-token \
  --ws-token-file ~/.codex/minedit/app-server.token
```

Copy the token:

```sh
cat ~/.codex/minedit/app-server.token
```

Then configure Minecraft:

```text
/minedit provider codex
/minedit codex url ws://codex-host:4500
/minedit codex token <token>
/minedit codex status
```

Use `ws://` only for localhost, VPN, or SSH-tunneled connections. For shared or remote networks, put app-server behind TLS and auth, then use `wss://`.

Codex model ids usually do not use the OpenRouter `openai/` prefix. Minedit strips `openai/` automatically, but `/minedit model gpt-5.5` is clearer for Codex.
