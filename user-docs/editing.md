# Editing

Use edit mode to change the selected area based on its current blocks:

```text
/minedit edit make the roof steeper and add windows
```

Use quick edit for small targeted patches:

```text
/minedit edit quick remove the flower and change the oak planks to spruce
```

Normal edit and quick edit use a compact line-aware representation of the current build. Models can emit small patches like:

- `api.replaceLine(...)`
- `api.clearLine(...)`
- `api.set(...)`
- `api.fill(...)`

Set quick edit effort:

```text
/minedit edit set quickeffort low
```

If an edit goes wrong:

```text
/minedit reset build
```
