# Troubleshooting

## Command Crashes Or Parse Errors

Use `/minedit ...` commands as documented here. Legacy top-level commands still exist, but the canonical grouped commands avoid ambiguous redirects.

## No Selection

Hold a stick, left click `pos1`, then right click `pos2`.

If you disabled selection mode:

```text
/minedit selection tool on
```

## Stop A Running Job

```text
/minedit stop
```

This requests cancellation for your current AI generation and removes your queued placement jobs.

## Provider Fails

Check status:

```text
/minedit status
```

For Codex:

```text
/minedit codex status
```

Confirm the server process can reach the configured URL and that any token is set in `config/minedit.properties` or the expected environment variable.

## Debug Files

When a model response fails, Minedit writes debug files to:

```text
config/minedit-debug/
```

Useful files:

- `last-prompt.txt`
- `last-response.txt`
- `last-build.js`

These files may contain prompts and generated code. Review them before sharing.
