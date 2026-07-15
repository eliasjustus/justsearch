---
evolution-rule: additive-optional
---
Tempdoc 737 §12c Phase 2a adds five optional fields to `InferenceRuntimeView`
(`/api/status`'s inference block): `chatEnabledSpec`, `engineState`, `engineReason`,
`procedure`, `leaseHolder`. These additively project the Head-side runtime authority
(`RuntimeSpec`/`RuntimeStatus`, `modules/app-services/.../runtimestate/`) onto the wire.

The existing `phase` field is unchanged (byte-identical producer logic) and is retained
as a deprecated alias per §12d — it retires after FE cutover to the new fields plus a
public deprecation window, not in this changeset.

No existing field is removed, renamed, or retyped. Backward-compatible with all existing
producers and consumers.
