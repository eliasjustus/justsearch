---
classification: declared-growth
tempdoc: 742
---

The `dead-code` (Knip) gate has been inert since its inception (tempdoc 530 sec 2.9): the
`tmp/knip-report.json` input was never produced by any script, so `gates/dead-code/baseline.txt`
sat empty and the gate passed vacuously on every run. Tempdoc 742 D2 wires a real producer
(`npm --prefix modules/ui-web run knip:report`) and, while investigating, found a second live
bug: the enforcer's report parser was written for a `report.issues` shape of
`{category: {file: [entries]}}`, but the installed `knip` (^6.20.0) JSON reporter actually emits
`{issues: [ {file, exports: [...], types: [...], ...}, ... ]}` (verified against
`node_modules/knip/dist/reporters/json.js`) - an array of per-file rows with per-category array
fields. The enforcer's generic-object branch silently matched the array (arrays are objects) and
mis-derived nonsense per-"path" counts keyed by issue-category names. Fixed the enforcer to add an
`Array.isArray(report.issues)` branch that sums per-file category-array lengths correctly, and
regenerated the self-test fixtures to use the real report shape rather than the previously
untested synthetic one. This changeset baselines the resulting honest count - 172 files, 633
total unused-export/type/file findings - as the ratchet's genesis point, so future PRs are
compared against real numbers instead of an empty file, and the ratchet can now shrink from here.
