# Release Process

Minedit uses SemVer in `gradle.properties`:

```properties
mod_version=1.5.1
```

Git tags include the `v` prefix:

```text
v1.5.1
v1.6.0-beta.1
```

Release title format:

```text
Minedit v1.5.1
```

## Public Jar Names

| Loader | Jar pattern |
| --- | --- |
| NeoForge | `Minedit-NeoForge-26.1.2-v<version>.jar` |
| Forge | `Minedit-Forge-1.20.1-v<version>.jar` |

The `mod_version` property does not include `v`; Gradle adds `v` to public jar archive versions.

## Build

```sh
./gradlew clean jar :forge1201:jar
npm ci
npm run docs:build
```

Expected public outputs:

```text
build/libs/Minedit-NeoForge-26.1.2-v<version>.jar
forge1201/build/libs/Minedit-Forge-1.20.1-v<version>.jar
user-docs/.vitepress/dist/
```

Do not publish `rhino-1.8.0-relocated.jar`; it is an internal shaded dependency input.

## Checklist

1. Update `mod_version` in `gradle.properties`.
2. Update `CHANGELOG.md`.
3. Run `./gradlew clean jar :forge1201:jar`.
4. Run `npm ci` and `npm run docs:build`.
5. Smoke check both jar names and timestamps.
6. Create and push tag `v<version>`.
7. Upload the two public jars to the GitHub release.
