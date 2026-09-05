---
classification: new-rule-registered
tempdoc: 579
---
New rule 33 (`frontend-stack-is-lit`) registered at `lint` tier in the CLAUDE.md
Hard Invariants table.

Tempdoc 579 (canonical-doc drift audit) found that the React → Lit frontend rewrite
(ADR-0032, graduated 2026-06-09) was never back-propagated to the older
architecture / how-to / behavioral docs: ~15 canonical docs still asserted React /
`.tsx` / Zustand as the current stack, including the first doc agents read
(`01-system-overview.md`). Root cause: the docs machinery had generation
(`llmstxt-generate`, `skills-sync`) and a canonical→noncanonical guard
(`verify-canonical-doc-links`), but nothing checked canonical claims against code —
a doc could say "React" forever and no gate fired.

Tier is `lint`: a bespoke fail-closed CI check
(`scripts/docs/check-frontend-stack-claims.mjs`, wired into `docs-lint.yml`)
flags present-tense `React` / `.tsx` / `Zustand` assertions in
`docs/{explanation,reference,how-to}`, exempting lines framed historically
(retired / superseded / "not React" / ADR-0032) and the `reference/issues/` bug
logs. Same category as rule #3 (`no-legacy-endpoints`, also `lint`): a custom
static check, not a discipline-gate-kernel gate, so the `Resolves to` column carries
no `gate:`/`hook:`/`archunit:` marker.

Rule anchored in `CLAUDE.md` (`<!-- rule:frontend-stack-is-lit -->`).
