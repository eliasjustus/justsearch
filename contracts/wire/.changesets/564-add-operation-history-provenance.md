---
evolution-rule: additive-optional
---
Tempdoc 564 (proto-parity X-cut). Added `InvocationProvenance provenance = 10` to
`OperationHistoryEntry` plus the new `InvocationProvenance` message (transport / executor /
initiator / occurred_at / signed_intent_token / correlation_id).

Closes the record↔proto drift where `OperationHistoryEntry.provenance` (slice 490 §4.B) had no
counterpart in `operation_history.proto` (563 §3 V3 / observations inbox). A message field has
wire presence (additive-optional): backward-compatible with all existing producers/consumers.
The new `ConversationShapeFixtureGenTest`-style record↔proto conformance test
(`OperationHistoryWireContractConformanceTest`, app-services) now guards this surface so the
drift cannot recur.
