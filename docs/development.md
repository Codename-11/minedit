# Development

## Requirements

- Java 25 for the NeoForge root build.
- Java 17 toolchain for the Forge 1.20.1 subproject.
- Node.js 18+ for the local bridge.
- Node.js 22 recommended for the VitePress docs site and CI.

## Build Commands

```sh
./gradlew compileJava
./gradlew :forge1201:compileJava
./gradlew jar :forge1201:jar
```

Docs:

```sh
npm ci
npm run docs:dev
npm run docs:build
```

## Working Tree Notes

This repo often has mixed work in flight. Before editing, check:

```sh
git status --short
```

Do not revert unrelated dirty files. User fixes, especially command-tree crash fixes, should be treated as intentional unless explicitly called out.

## Command Surface

Use `/minedit ...` in new docs and UI. Legacy top-level commands remain for compatibility.
