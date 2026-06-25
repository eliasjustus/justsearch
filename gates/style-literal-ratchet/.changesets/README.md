# style-literal-ratchet changesets

Declare a justified growth in raw style literals (z-index / transition /
font-size) above the per-file baseline (`scripts/ci/style-literal-ratchet-baseline.v1.json`).

Each changeset is a `*.md` with frontmatter:

```yaml
---
classification: declared-growth   # | merge-import | emergency-override
tempdoc: 574                       # or adr:
---
Why this raw literal cannot route through its --z-overlay-* / --duration-* /
--font-size-* token (yet).
```

The default discipline is **shrink** — route the value through its token and run
`node scripts/ci/check-style-literal-ratchet.mjs --rebalance`. A changeset is the
escape hatch when a literal genuinely cannot be tokenized in this change.
