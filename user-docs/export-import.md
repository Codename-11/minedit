# Export And Import

Use manual export when you want to call a model outside Minedit:

```text
/minedit build export <prompt>
```

This writes the exact build prompt to:

```text
config/minedit-debug/export-prompt.txt
```

It also creates this import file if needed:

```text
config/minedit-debug/import-build.js
```

Send the exported prompt to a model yourself, then paste either the full response or just the returned `function build(api) { ... }` code into `import-build.js`.

Select the same footprint in Minecraft and run:

```text
/minedit build import
```

For very small snippets, pasted import also works:

```text
/minedit build import function build(api) { /* ... */ }
```
