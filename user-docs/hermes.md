# Hermes

Hermes mode sends requests directly to a Hermes runs endpoint. The default URL is:

```text
http://127.0.0.1:8642/v1
```

Configure Minecraft:

```text
/minedit provider hermes
/minedit hermes url http://127.0.0.1:8642/v1
/minedit hermes token <token>
/minedit model gpt-5.5
```

If no Hermes token is saved, Minedit uses `HERMES_GATEWAY_TOKEN` when it is available in the server environment.

Hermes approval requests are surfaced as progress messages. Minedit does not grant approvals automatically.
