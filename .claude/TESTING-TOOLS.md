# Testing add-ons for Claude

These make Claude Code test this project better. They change how Claude works; nothing here ships in the phone app.
All versions are fixed and usage stats are turned off.

| Add-on | From | Used for |
|---|---|---|
| Maestro MCP (`.mcp.json`, CLI 2.12.1) | mobile.dev (official) | Drive the emulator, write repeatable UI flows (escape tests) |
| mobile-mcp 1.0.20 (`.mcp.json`) | mobile-next | Live tap/swipe/screenshot/logcat on an emulator or phone over adb |
| Playwright MCP 0.0.86 (`.mcp.json`) | Microsoft | Click through the owner and staff portals, screenshots |
| webapp-testing skill (`.claude/skills/`) | Anthropic (anthropics/skills, Apache-2.0) | Start the server and test the portals end to end |
| superpowers | obra, via Anthropic's plugin directory (pinned commit) | Test-first fixes, root-cause debugging, verify before "done" |
| Trail of Bits: insecure-defaults, sharp-edges, static-analysis, mutation-testing, supply-chain-risk-auditor | Trail of Bits (pinned commit) | Fail-open bugs, weak tests, risky dependencies |
| pr-review-toolkit | Anthropic | Missing tests and swallowed errors in each change |

Setup on a new machine:
- `scripts/setup-test-tools.sh` installs the pinned Maestro CLI to `~/.maestro` (checksum-checked; needs Java 17+).
- mobile-mcp and Maestro need `adb` and a running emulator or phone (CI emulator or the PC emulator).
- Cloud features of mobile-mcp and Maestro are blocked in `.claude/settings.json`.
- Python Playwright for the webapp-testing skill: `pip install playwright` (in the cloud container, launch Chromium with `executable_path="/opt/pw-browsers/chromium"`).

Deliberately not added: Semgrep plugin (uploads code to its servers), Android pentest kits (only for an isolated machine), Anthropic security-guidance (heavy hooks on every edit).
