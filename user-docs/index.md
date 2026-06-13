---
layout: home

hero:
  name: Minedit
  text: AI building tools for Minecraft worlds.
  tagline: Select a footprint, describe the build or edit, and let your configured model generate block placement code for the selected area.
  image:
    src: /minedit-mark.svg
    alt: Minedit mark
  actions:
    - theme: brand
      text: Get Started
      link: /getting-started
    - theme: alt
      text: Commands
      link: /commands

features:
  - title: Select With A Stick
    details: Left click a block for pos1 and right click a block for pos2. The selected X/Z footprint becomes the build area.
  - title: Choose Your Provider
    details: Use OpenRouter, direct Codex app-server, Cursor through the bridge, or a Hermes runs endpoint.
  - title: Build, Edit, Or Chat
    details: Generate full builds, run staged passes, patch existing structures, or ask the agent for plain guidance.
---

## What Minedit Does

Minedit is an experimental server-side Minecraft mod for generating and editing structures with AI. It sends your prompt and selected footprint to the provider you configure, parses returned builder code, and queues block placement in the world.

Install it on the server for commands and block placement. Install it on the client too if you want selection particles and the `/minedit gui` control screen.
