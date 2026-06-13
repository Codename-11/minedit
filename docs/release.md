# Release Notes

See the root [RELEASE.md](../RELEASE.md) for the canonical release checklist.

## CI

The GitHub Actions build should:

1. Install Node dependencies with `npm ci`.
2. Build VitePress docs with `npm run docs:build`.
3. Build both public jars with `./gradlew jar :forge1201:jar`.
4. Upload only:
   - `build/libs/Minedit-NeoForge-*-v*.jar`
   - `forge1201/build/libs/Minedit-Forge-*-v*.jar`

Do not upload `build/libs/rhino-1.8.0-relocated.jar`.
