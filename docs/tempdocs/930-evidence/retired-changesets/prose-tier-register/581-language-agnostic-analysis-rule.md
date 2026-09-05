---
classification: new-rule-registered
tempdoc: 581
adr: 0043
---
New rule 34 (`language-agnostic-analysis`) registered at `lint` tier in the CLAUDE.md
Hard Invariants table.

Tempdoc 581 / ADR-0043 makes the standing invariant explicit: the engine is multilingual
by construction and stays that way with zero per-language engineering. Analysis is a pure
function of Unicode (ICU + NFC + lowercase), never of detected language. The rung-1 collapse
(581 §13) removed the inert per-language scaffolding (the `content_{en,de}` fields, the
`en`/`de` analyzers, the empty per-language synonym files) and closed the analyzer-provider
`enum` in `analyzers-catalog.schema.json` to `{icu, keyword}`.

Tier is `lint`: a bespoke CI check (`scripts/ci/check-language-agnostic-analysis.mjs`, wired
into `ci.yml` + the CLAUDE.md pre-merge list) is the rung-2 backstop that forbids reintroducing
a per-language analysis artifact — a non-`*` locale analyzer, an out-of-enum provider, a
`content_<lang>` field, a non-empty per-language synonym file, or a `content_<lang>` query
literal. Same category as rule #3 (`no-legacy-endpoints`, also `lint`): a custom static check,
not a discipline-gate-kernel gate, so the `Resolves to` column carries no
`gate:`/`hook:`/`archunit:` marker.

Rule anchored in `CLAUDE.md` (`<!-- rule:language-agnostic-analysis -->`).
