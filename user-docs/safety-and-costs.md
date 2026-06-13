# Safety And Costs

Minedit is experimental. Back up worlds before testing large builds, staged builds, agent builds, or edits.

## Provider Cost

Minedit sends prompts to the provider you configure:

- OpenRouter requests use your OpenRouter API key.
- Codex requests use your Codex/OpenAI login and may consume Codex, ChatGPT, or OpenAI plan limits.
- Cursor bridge requests use your local Cursor login or API key and may consume Cursor plan limits.
- Hermes requests use the configured Hermes endpoint and token.

Depending on provider, model, account, and usage, requests may cost money or consume plan limits. You are responsible for all usage and charges.

## Secrets

OpenRouter API key, saved Codex app-server token, and saved Hermes token are stored in plaintext in:

```text
config/minedit.properties
```

Do not share this file, screenshots of it, modpacks containing it, or support logs that include it.

## Generated Code

Models can generate poor block plans. Use `/minedit reset build` and world backups while testing. Minedit checks Minecraft block survival rules before placement, so unsupported fragile blocks may be skipped.
