// Self-test fixture (negative): a kind:projection surface guarded only by a non-conformance test —
// Check 6 (non-conformance-guard) must flag it. No span literal, no SearchTrace reference (so it
// trips ONLY the conformance-guard check, isolating that signal).
class PlainProjection {}
