# Live floor-smoke fixture — tempdoc 624 remaining verification

The Track-A + Track-B implementation is **complete and fixture-tested**
(`python -m pytest scripts/jseval/tests` → 902 passed). The **one remaining step**
is a *live* agent-eval A-vs-C run that produces a real `utility-comparison.v1`
record. It needs the shared dev stack + the `claude` CLI (verified working).

This is a **contamination-free** micro-corpus: the facts (Captain *Mortimer Flux*,
engineer *Lila 034*) are fabricated, so the agent cannot answer from training — it
must read files (condition A) or retrieve via JustSearch (condition C). That is why
`--contamination-class private-synthetic` is correct here. Cost ≈ $0.20–0.60.

## Exact remaining live steps (run from repo root, with the dev stack FREE)

```bash
# 1. Start the stack (MCP tool) and note the API port P:
#      justsearch_dev_start            # -> apiPort = P
# 2. Ingest this corpus (MCP tool):
#      justsearch_dev_ingest  paths=["scripts/jseval/util-smoke/corpus"]
# 3. Write an MCP config pointing at the stack:
echo '{"mcpServers":{"justsearch":{"url":"http://127.0.0.1:P/mcp"}}}' > /tmp/js-mcp.json

# 4. Baseline arm A (generic file tools only):
python -m jseval agent-eval \
  --queries scripts/jseval/util-smoke/queries.json \
  --corpus-dir "$(pwd)/scripts/jseval/util-smoke/corpus" \
  --condition A --model haiku --max-queries 2 \
  --output-dir scripts/jseval/util-smoke/out

# 5. With-tool arm C (JustSearch MCP only; file tools disabled):
python -m jseval agent-eval \
  --queries scripts/jseval/util-smoke/queries.json \
  --corpus-dir "$(pwd)/scripts/jseval/util-smoke/corpus" \
  --mcp-config /tmp/js-mcp.json \
  --condition C --model haiku --max-queries 2 \
  --output-dir scripts/jseval/util-smoke/out

# 6. Compose the canonical record + the Inspect-EvalLog projection:
python -m jseval utility-compose \
  --run A=scripts/jseval/util-smoke/out/agent-eval-A-haiku.json \
  --run C=scripts/jseval/util-smoke/out/agent-eval-C-haiku.json \
  --dataset golden/util-smoke --model haiku \
  --search-config-key live-backend \
  --contamination-class private-synthetic \
  --output-dir scripts/jseval/util-smoke/out
# -> scripts/jseval/util-smoke/out/utility-comparison.v1.json
#    scripts/jseval/util-smoke/out/utility-comparison.inspect.json
```

For the **floor data-run proper** (the haiku-only publishable floor, not just this
smoke), use the MultiHop-RAG corpus (609 articles — download from HuggingFace
`yixuantt/MultiHopRAG`, extract to .md per tempdoc 346) with
`--seeds 5 --max-queries 50` per condition, then `utility-compose` over the
seed-suffixed outputs. `--contamination-class public-pre-cutoff` for MultiHop-RAG.
