# `.claude/` — agent development configuration

Configuration for developing JustSearch with AI coding agents (Claude Code): project
instructions (`CLAUDE.md`), always-loaded rules (`rules/`), on-demand skills (`skills/`), and
hook wiring (`settings.json`).

**You don't need any of this to use, build, or contribute to JustSearch.** It is published for
transparency — it documents our maintainer development process (see [`/MAINTAINING.md`](../MAINTAINING.md)).
To contribute, follow `README.md` → `CONTRIBUTING.md`; you can ignore this directory entirely.

By policy, only the **universally-safe discipline guards and hints** are committed in
`settings.json` (e.g. blocking destructive git in the main checkout, just-in-time edit hints) —
they help any contributor and depend on no maintainer-only infrastructure. Maintainer-local
analytics hooks (telemetry, session tooling) are **not** committed here; they live in a
maintainer's own gitignored `settings.local.json`.
