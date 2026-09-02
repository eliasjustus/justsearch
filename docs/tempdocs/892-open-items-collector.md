---
title: "Open-items collector: a script that harvests every tempdoc's open-items/deferred/follow-up section into one report, plus a one-time harvest of tempdocs 700-887"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L15
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 872-memory-retirement            # retired the observations store (565 notes, none read)
  - 821-root-cause-debt-charter      # §2c was the last cross-tempdoc harvest (UNVERIFIED candidates)
  - 98 / 214 / 238 / 512 / 799       # earlier one-off harvests
  - 787-post-arc-platform-hygiene-sweep  # §A item 1 built a tempdoc status-vs-merged linter (gate mode OFF)
  - 884-decision-review-lane-b-governance-loop  # owns ADR follow-through — NOT this lane
---

# 892 — Open-items collector

## Briefing for the agent picking this up

Fresh start. Read this file, then 887 §X2 and Appendix A10 §10.7. Work in a worktree. This is a
docs-tooling lane: Node scripts under `scripts/ci/` with a `*.test.mjs` sibling (the repo's
convention; `scripts/ci/run-all-tests.mjs`-style discovery), no product code. Tempdoc conventions
are in `docs/tempdocs/README.md` (cite by slug, not number). The harvest output goes **into
tempdoc 887 as a new §H**, not into a new store — the whole point of 872 was that a store nobody
reads is a pile. Two PRs: (1) the script + test, (2) the harvest appended to 887.

## Thesis

44% of tempdocs 700-880 (77 of 175) carry an open-items / remaining / deferred / follow-up
section. Sampling 880 §C showed its items unacted-on: routed to a tempdoc still `open` since
2026-05 (532), a stale test citation still in place (`AgentTimeoutsTest.java:224`), and an item
appearing in no later tempdoc. CLAUDE.md's routing rule is prose-tier; nothing collects. Six
prior harvests were each one-off. The retired observations store also held accepted gaps that
now have no register (`03-knowledge-server.md:283`).

## Scope

1. **`scripts/ci/report-open-items.mjs`.** Scans `docs/tempdocs/*.md`; finds sections whose
   heading matches `/open items|remaining|deferred|follow-?ups?|residue|unverified/i`; extracts
   unchecked task boxes (`- [ ]`) and bullet items under them; emits NDJSON + a Markdown table:
   tempdoc slug, section, item text (first 160 chars), tempdoc `status` frontmatter, tempdoc
   `updated` date, and a **liveness hint** — whether any tempdoc with a higher number mentions
   the item's first distinctive 4-word phrase (cheap grep; report-only). `--since <number>`
   filter. Report mode only (exit 0); this is a collector, not a gate.
2. **Test** with fixtures: three synthetic tempdocs (one with `## Open items` + boxes, one with
   `### Remaining (level 2)`, one with none) → expected rows.
3. **One-time harvest** of 700-887 into 887 §H: run the script, then hand-triage each row into one
   of: `done` (evidence: file:line or later tempdoc), `absorbed` (name the 887 lane or 882 lane
   that owns it), `dead` (its reason expired — say why), `orphan` (nothing owns it). Orphans get a
   proposed home (an existing lane id or "owner decision"). Include the three accepted gaps from
   `03-knowledge-server.md:283` as rows.
4. **Hook delivery (small).** Extend the existing `docs-granularity-hint` or add a Stop-time
   line: when a session's edited tempdoc gained an open-items section, print the script's row
   count for that tempdoc with the reminder "route, don't log" (CLAUDE.md
   `log-pre-existing-issues`). Non-blocking. Register it in `governance/agent-hooks.v1.json`
   with a real bite (hook-integrity gate).

## Acceptance criteria

- `node scripts/ci/report-open-items.mjs --since 700 --format md` produces the table; the test
  passes under `node scripts/ci/run-all-tests.mjs`-style discovery (confirm the discovery path
  used by `ci.yml:118-119` / `:223-224` and wire the test the same way).
- 887 §H exists with every row triaged into one of the four dispositions; orphan count stated.
- `node scripts/governance/run.mjs --gate hook-integrity --mode gate` green after item 4.
- `node scripts/ci/check-tempdoc-numbers.mjs` green (you edit 887, you do not create numbers).

## Constraints

- Do **not** parse ADR `## Consequences`/`## Follow-up` — that is the founder's lane B (884)
  territory; leave 10.8 alone and say so in §Status.
- Do not resurrect `note-observation.mjs` or any inbox file.
- Non-goals: fixing the harvested items (route them), touching `CLAUDE.md` (budget ratchet).

## Status

(unstarted)
