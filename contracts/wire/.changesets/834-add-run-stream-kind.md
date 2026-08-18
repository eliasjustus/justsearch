---
evolution-rule: additive-optional
---
Tempdoc 834 S3a widens `SseEnvelope.stream_id`'s validation pattern from
`^(registry|surface|system):[a-z][a-z0-9-]*$` to
`^(registry|surface|system|run):[a-z][a-z0-9-]*$`, admitting a fourth stream kind
`run` for per-run observation streams (`run:run-<uuid>`). The Java authority is
`StreamId.PATTERN` (`modules/app-api/.../stream/StreamId.java`); this keeps the wire
constraint in lockstep with it.

No field is added, removed, renamed, or retyped, and the slug rule is unchanged
(letter-initial `[a-z][a-z0-9-]*`). The change only *relaxes* a value constraint: every
stream_id accepted before is still accepted, so existing producers and consumers are
unaffected. No `run:`-kinded stream is emitted yet — S3a ships the kind only; the run
channels that use it land in S3b.

**No VERSION bump accompanies this changeset**, deliberately: a `(buf.validate.field)`
pattern relaxation is structurally invisible to `buf breaking`, so the gate's
`contract-governance/phantom-version` rule rejects a bump ("VERSION bumped (patch) but no
contract changes detected"). Verified both ways against the gate — 1.0.4 fails,
1.0.3 + this changeset passes.
