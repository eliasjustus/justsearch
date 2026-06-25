# atom-fork-ratchet changesets

Declare a justified growth in raw atom-class CSS rules (`.badge` / `.chip` /
`.pill` / `.tag` / `.status-dot` / `.outcome-tag`) above the per-file baseline
(`scripts/ci/atom-fork-ratchet-baseline.v1.json`).

Frontmatter: `classification: declared-growth | merge-import | emergency-override`
plus a `tempdoc:` / `adr:` reference. The default discipline is to **compose a
registered atom** (`jf-status-badge` / `jf-status-dot` / `jf-button` /
`jf-error-alert`) or mark a genuinely-distinct chip `ahaDistinct` in
`governance/atom-facets.v1.json` — a changeset is the escape hatch only.
