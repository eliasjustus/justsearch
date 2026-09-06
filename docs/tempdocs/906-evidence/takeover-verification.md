# Tempdoc 906 takeover verification — 2026-09-05

Moved from the takeover record; these are historical investigation results.

### Experiments and verification record

All probes ran under Node `v24.12.0` from the investigation worktree. T-E1 used the
already-installed TypeScript compiler in the main checkout read-only; no repository
source was transformed on disk and no request reached a backend.

**T-E1 — actual search-store execution, mocked transport and timers.** Run this
JavaScript with `node` (set `TYPESCRIPT_LIB` to an installed TypeScript package):

```js
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require(process.env.TYPESCRIPT_LIB);
const assert = require('node:assert/strict');
let calls = 0, reads = 0;
const out = {};
const authorizedFetch = async () => {
  calls++;
  return { ok: false, status: 502, json: async () => {
    reads++;
    return { error: 'The search index is unavailable.',
      errorCode: 'INDEX_UNAVAILABLE', i18nKey: 'errors.INDEX_UNAVAILABLE',
      retryable: true };
  } };
};
const context = {
  exports: out, AbortController, performance, console,
  window: { setTimeout: () => 1, clearTimeout: () => {} },
  require: (id) => {
    if (id.includes('authorizedFetch')) return { authorizedFetch };
    if (id.includes('searchFiltersState')) return {
      getFilters: () => ({}), hasActiveFilter: () => false,
      getFacetSelections: () => ({}) };
    if (id.includes('schema-types')) return {};
    throw new Error('Unexpected dependency ' + id);
  },
};
const source = fs.readFileSync(
  'modules/ui-web/src/shell-v0/state/searchState.ts', 'utf8');
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: {
  module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
} }).outputText, context);
out.setQuery('takeover-probe');
out.submitSearch();
setImmediate(() => {
  assert.equal(calls, 1);
  assert.equal(reads, 0);
  assert.equal(out.getSearchState().error, 'HTTP 502');
  console.log({ calls, errorBodyReads: reads, error: out.getSearchState().error });
});
```

Observed: `{ calls: 1, errorBodyReads: 0, error: 'HTTP 502' }`. The one-request
assertion ensures this cannot pass merely because search was never invoked. This
is evidence of current defective behavior, not a regression test of a fix.

**T-E2 — source census.** Node regex extraction of API lines matching
`^\s*([A-Z][A-Z0-9_]*)\(ErrorClass\.`, bare agent constants including the final
undelimited member, `public static final String` ingestion values, and the draft's
`REASON_CODE_LABELS` keys yielded:

```text
API codes: 130; agent codes: 14; shared names: INTERNAL_ERROR
Ingestion codes: 24; draft labels: 9; obsolete draft labels: 0
Missing draft labels (15): SUCCESS_EMPTY, EXTRACTION_DROPOUT_PENDING_FALLBACK,
EXTRACTION_DROPOUT_UNRECOVERED, NON_REGULAR_SOURCE, MISSING_AT_PROCESSING,
DELETED_OR_MISSING, STALE_AFTER_EXTRACTION, DELETED_AFTER_SNAPSHOT,
SIZE_CHANGED_AFTER_SNAPSHOT, FILE_KEY_CHANGED_AFTER_SNAPSHOT, UNREADABLE,
IO_ERROR, SANDBOX_FAILED, WRITE_FAILED, WRITE_UNAVAILABLE_DRAINING
```

Implementation correction (2026-09-06): this probe undercounted the baseline.
There were already **26** constants; its line-oriented extraction missed multiline
declarations `MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT` and
`SOURCE_KIND_CHANGED_AFTER_SNAPSHOT`. The raw probe output above is retained as
history, not the current census. §U3 and its regression test cover all 26.

**T-E3 — extractor suitability.** Import `extractEnumConstants` and
`checkCorrespondence` from `scripts/ci/check-search-degradation-reason-codes.mjs`
and pass the three current Java sources named in correction 5. Observed:

```text
ApiErrorCode: 0 extracted
AgentErrorCode: 13 extracted; UNSUPPORTED_RESUME_STATE omitted
TerminalDisposition: 4 extracted; CANCELLED omitted
checkCorrespondence({enumCodes: new Set(), wordingCodes: new Set(),
  noWordingExempt: [], feDerived: []}): []
```

**Existing checks run, all passed:**

- `node scripts/ci/check-readiness-reason-codes.mjs`: 56 emittable codes,
  50 wording rows; producer-reference direction passed across 1,654 Java sources.
- `node scripts/ci/check-search-degradation-reason-codes.mjs`: query degradation
  28 codes / 17 worded / 11 exempt; cross-encoder skip 12 / 5 / 7.
- `node --test scripts/ci/check-readiness-reason-codes.test.mjs`: one test file
  passed, with its 19 internal assertions.

These green checks coexist with T-E1 because they verify different seams. They
are not end-to-end UX evidence. Full application compilation, Vitest, Java tests,
live MCP/agent queries and measured screen accessibility remain unrun because no
product or gate implementation was performed.

