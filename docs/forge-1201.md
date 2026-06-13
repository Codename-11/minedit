# Forge 1.20.1 Compatibility

Forge support lives in `forge1201/`.

The subproject uses `generateForgeCompatSources` to copy shared Java sources and rewrite narrow NeoForge API differences. Loader-specific client files stay in `forge1201/src/main/java`.

## Key Rules

- Keep loader differences narrow.
- Exclude NeoForge-only client classes from generated Forge sources.
- Prefer shared code for provider, build, selection, and command behavior.
- Add a new compatibility subproject when a Minecraft or loader version needs different APIs.

## Verification

```sh
./gradlew :forge1201:clean :forge1201:compileJava
./gradlew :forge1201:jar
```
