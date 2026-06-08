# Changelog

## 1.2.0

Release-readiness update for public testing.

### Added

- `/build stages <prompt>` for multi-call staged generation:
  - foundation and frame
  - walls, openings, doors, and windows
  - roof, ceilings, stairs, and vertical access
  - interior lighting and furniture
  - exterior detail and landscaping
  - final corrections and polish
- Codex local agent mode with tool-driven `step-by-step` placement.
- OpenRouter streaming progress for builds/edits.
- OpenRouter usage and cost reporting, including BYOK upstream inference cost when available.
- `/status` command for provider/model/selection/generation/queue state.
- README release documentation, warnings, examples, and release checklist.

### Changed

- Normal build mode clears existing blocks in the selected footprint before placing the generated build.
- Build prompts now strongly emphasize interiors, lighting, furniture, roof correctness, paths, doors, block orientation, support-sensitive blocks, and fluid containment.
- Edit and quick edit modes use compact line-aware patches for more token-efficient changes.
- Codex bridge integration now supports dynamic tools for agent builds.

### Notes

- OpenRouter API keys are stored in plaintext at `config/minedit.properties`.
- Codex local bridge usage may consume Codex, ChatGPT, or OpenAI plan limits.
- This is still experimental. Back up worlds before testing.
