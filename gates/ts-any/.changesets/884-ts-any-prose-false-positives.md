---
classification: merge-import
tempdoc: 884
---

**None of these five "any-casts" is an `any` cast.** All six counted occurrences are the English
word "any" inside a `//` or `/** */` comment, matched by the enforcer's `:\s*any\b` / `\bas\s+any\b`
alternatives because `countAny` (`scripts/governance/gates/ts-any/enforcer.mjs:29-31`) runs its
regex over raw file text without stripping comments or strings:

| file:line | counted text |
|---|---|
| `modules/ui-web/src/shell-v0/components/chat/citationResolve.test.ts:97` | `* are the mutation probe: any route that lets a lexical score reach a tier fails them.` |
| `modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts:509` | ``* nothing in it is a reference: any `[n]`/`(n)` is muted and named).`` |
| `modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts:768` | ``* the predicate is deliberately broad: any `[n]` or `(n)`,`` |
| `modules/ui-web/src/shell-v0/state/indexingProgress.ts:130` | `* whenever there is no honest basis: any phase but` |
| `modules/ui-web/src/shell-v0/views/search-v3/sv3-sessions.test.ts:615` | `// set of three can never be described as any other number.` |
| `modules/ui-web/src/shell-v0/views/searchResultViewModel.ts:51` | ``* not a `justsearch-help` string check: any named non-default`` |

Classified `merge-import` rather than `declared-growth` because that is the provenance-accurate
word available in this gate's vocabulary (`scripts/governance/gates/ts-any/classifications.mjs`):
the counts arrived through merged history — prose written by the PRs that shipped these files — not
from the branch declaring this changeset. Tempdoc 884's lane B PR 2 changes three `.ts` files under
`modules/ui-web/src` (the Surface projection work) but none of the six flagged sites, and cannot
have introduced them. `declared-growth` would assert that type
safety was knowingly traded away, which is false; leaving the gate red would leave a real signal
buried under six false ones. The 2026-07-14 precedent is
`gates/ts-any/.changesets/727-baseline-reconciliation-preexisting-any-casts.md`, which reconciled
pre-existing casts the same way.

**Root cause, routed as a tracked item, not left as a note.** The defect is in the gate, not in the
frontend: `ANY_PATTERN` counts matches inside comments and string literals. The fix is to strip
comments before counting — the repo already has the pattern in
`scripts/ci/check-readiness-reason-codes.mjs` (`stripJavaComments`, `:116-148`), which exists for
exactly this failure mode — and then rebalance `gates/ts-any/baseline.txt`, whose 18 rows are
themselves counted the same unstripped way and so may be overstated. That work is recorded in
tempdoc 884's open items with this evidence; it is deliberately not done here, because changing a
kernel gate's counting semantics repo-wide is not a ride-along inside a governance-loop PR that
already touches the sibling `adr-coverage` gate.

Until it lands, a genuine new `any` cast in one of these six files would still be caught: the gate
compares per-file counts, so a real cast pushes the count above the number this changeset covers.
