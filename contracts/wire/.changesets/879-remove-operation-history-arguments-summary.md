---
evolution-rule: remove
---
Tempdoc 879 (make `AuditPolicy` mean something) removes
`OperationHistoryEntry.arguments_summary` (field 3) from `operation_history.proto`.

The field was the wire counterpart of the Java record component of the same name, which
existed only to carry a redacted argument summary under `AuditPolicy.FULL_PAYLOAD`. No
Operation ever declared `FULL_PAYLOAD`, no producer ever summarised arguments (the
executor hard-coded `Optional.empty()`), and no consumer — backend, `ActionLedgerProjection`,
or FE — ever read it. `FULL_PAYLOAD` is deleted in the same change, so the field could
never again be non-absent; keeping it would advertise redaction machinery that does not
exist.

Field number 3 and both names (`arguments_summary` / `argumentsSummary`) are `reserved`, so
the number can never be reused and deserializers that previously accepted the field stay
wire-compatible. proto3 readers tolerate the absent field; the Head is the only producer and
is rebuilt with its consumers.

Breaking by design, and classified as such: `buf breaking` under the `FILE` category flags a
field deletion regardless of the reserve, which is the correct signal for a field leaving the
contract. VERSION bumped 1.0.3 → 2.0.0.

The surviving `AuditPolicy` axis (`NONE` / `METADATA_ONLY`) is now enforced at dispatch —
`OperationExecutorImpl.emitHistory` suppresses the history entry for `NONE` — so every entry
that reaches this message is metadata by construction, matching `METADATA_ONLY`'s contract.
