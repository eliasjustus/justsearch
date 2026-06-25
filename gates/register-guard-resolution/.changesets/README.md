# register-guard-resolution changesets

Author a changeset here (one `<id>.md` with frontmatter `classification: guard-downgrade` plus a
`tempdoc:`/`adr:` reference and a reason) when an entry's guard in a guard-string register
(`governance/execution-surfaces.v1.json` / `operation-surfaces.v1.json`) is **intentionally weakened**
— a real `gate:`/`test:` guard replaced by `exempt:<reason>` (or removed). This is the §5
no-silent-downgrade escape: the weakening is allowed, but only when explicitly classified and justified,
so a guard cannot silently drop a rung.

A bare `self`/`none-yet`/absent guard is never allowed (§3.1 invalid-guard-form) and has no changeset
escape — use `exempt:<reason>` instead.
