# Build Modes

## Normal Build

One model call generates the whole build:

```text
/minedit build a cute house
```

Normal build clears the selected footprint before placing generated blocks.

## Staged Build

```text
/minedit build stages a detailed wizard tower
```

The staged builder runs focused passes:

1. Foundation and frame
2. Walls, openings, doors, and windows
3. Roof, ceilings, stairs, and vertical access
4. Interior lighting and furniture
5. Exterior detail and landscaping
6. Final corrections and polish

Staged builds usually cost more than normal builds but give the model more focus per phase.

## Agent Build

```text
/minedit build agent <prompt>
```

Agent mode works with Codex, Cursor, and Hermes. It asks the configured agent provider to draft, preview, and revise before Minecraft places the final build.

## Step-By-Step Agent

```text
/minedit build agent step-by-step <prompt>
```

Step-by-step is available for providers that can emit placement batches. Codex direct app-server mode emits one final batch. Cursor uses the bridge phased generator.

## Chat Build

```text
/minedit chat build a small fountain here
```

If a selection exists and the message clearly asks for block changes, chat mode may queue build code. Plain questions return chat text only.
