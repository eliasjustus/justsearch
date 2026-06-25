# URL grammar conformance corpus

Per tempdoc 487 §5: the canonical cross-language URL grammar conformance
corpus. Java + TS implementations of the `justsearch://` URL parser
both test against this corpus; drift between implementations fails one
or both conformance tests.

## Files

- **`v1.json`** — the active corpus. Read by:
  - `modules/ui-web/src/shell-v0/router/parser.conformance.test.ts`
    (TS side, via Vitest).
  - `modules/app-services/src/test/java/.../MarkdownTextExtractorConformanceTest.java`
    (Java side, ships in slice 487 Phase 2.2 alongside `MarkdownTextExtractor`).

- **`generate.mjs`** — catalog-walker that regenerates `v1.json` from a
  live JustSearch backend. Reads `/api/registry/operations` +
  `/api/registry/surfaces` and emits one fixture per (Op | Surface) ×
  {bare, scalar-arg, array-arg, enum-arg} + a fixed set of negative
  cases. **Requires a running dev stack** at the supplied
  `--api-base-url`.

## When to regenerate

The corpus is hand-curated for the slice 487 V1 ship; positive cases
cover the grammatical shapes the parser handles (Navigation /
Invocation × {bare, scalar, array, enum, percent-encoded}), and
negative cases cover the syntactic-rejection paths (wrong scheme,
unknown host, malformed pathname).

**Regenerate from the live catalog when:**

- A new Operation or Surface ships with a novel input-schema shape not
  covered by the existing fixtures.
- The parser's grammar evolves (e.g., a new URL shape like
  `justsearch://prompt/<id>` lands).
- A bug-class is discovered that the existing fixtures miss; add the
  regression fixture and re-run both ports' conformance tests.

The split is deliberate (tempdoc §5):

- **Syntactic** (what the parser checks): URL shape, scheme, host,
  pathname structure, id regex `/^[A-Za-z0-9_.\-]+$/`, percent
  encoding. Stable; small.
- **Semantic** (what the resolver checks at runtime): id resolves
  against the live catalog; args validate against the destination's
  `inputSchema`. Catalog-derived; changes with every catalog edit.

The corpus tests **syntactic** behavior. Semantic resolution is tested
in catalog-aware integration tests, not here.

## Adding a fixture

Edit `v1.json`. Each fixture is one of:

```json
{
  "id": "kebab-case-id",
  "input": "justsearch://...",
  "expected": { "kind": "navigate" | "invoke", "target": "...", "state" | "args": {...} }
}
```

or, for a parse-rejected case:

```json
{
  "id": "neg-...",
  "input": "...",
  "expected": null
}
```

or, for an `extractUrls` test (used by Markdown-text extraction):

```json
{
  "id": "extract-...",
  "input": "...prose with embedded URLs...",
  "extract": [
    { "url": "justsearch://..." }
  ]
}
```

After editing, run both ports' conformance tests:

```bash
cd modules/ui-web && npm run test:unit:run -- src/shell-v0/router/parser.conformance.test.ts
./gradlew.bat :modules:app-services:test --tests "*MarkdownTextExtractorConformanceTest*"
```

Both must pass; failure indicates either a fixture error or a
parser-drift between Java and TS. The cross-language conformance is
the substrate's anti-drift mechanism (tempdoc §5 / §3.7).
