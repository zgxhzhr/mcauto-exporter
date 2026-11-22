# Agent notes

This file is for automation authors.

## Current state

v0.1 is GUI-first. There is no job-file protocol yet; do not assume
`-Dmeowarchive.job` exists in this build.

Working interfaces:

- Client command: `/meowarchive` opens the exporter screen.
- Output: `config/meowarchive.json` → `outputDir`.
- Machine-readable status: `<output>/status.json`.
- Data: `<namespace>/<namespace>_items.json`, `<namespace>/<namespace>_entities.json`, `<namespace>/<namespace>_effects.json`, `<namespace>/<namespace>_all.json` (all entries, each with `kind`).
- Human preview: `<output>/<namespace>/preview.html` (self-contained, icons inlined).
- Icons: `<output>/<namespace>/icons/<path>_<size>.png`.

## For automation

- Launch the client with a world (quick play), wait for the player to join,
  then send `/meowarchive` only if you are driving the GUI. There is no headless mode.
- Do not reflect into Minecraft classes: Fabric runtime names are intermediary.
- Read `status.json` for `state` (`DONE` / `FAILED`) and counts.
- `status.json` is written at the end of a task; it is not a live heartbeat.
- Icon files are written as each target completes, before JSON is written.

## Roadmap (not implemented)

- `-Dmeowarchive.job=<path>` job file, `events.jsonl`, sentinels, exit codes.
- CLI-only mode without opening a screen.
- Resume / cache.
