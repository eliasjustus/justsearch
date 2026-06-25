---
title: "353: Agent Friction Log"
type: tempdoc
status: done
created: 2026-03-24
updated: 2026-03-24
---

> NOTE: Noncanonical doc. Running log of agent pain points encountered
> during real sessions, with proposed fixes. Add new entries as they
> come up. Items here are candidates for future work, not commitments.

# 353: Agent Friction Log

## Backend end-to-end verification (session: 350/351)

### Complaints

**No documented verification command.** When an agent modifies code
that affects `/api/status`, gRPC proto messages, or enrichment
behavior, there's no documented way to verify it end-to-end. The
correct invocation is:
```
cd scripts/jseval && python -m jseval run \
  --start-backend --clean --pipeline \
  --dataset scifact --modes lexical --max-queries 5
```
This knowledge exists only implicitly in jseval's CLI options. An
agent must discover it by reading jseval source, burning context.

**Stale backends hold ports silently.** A Java process from a
previous session held port 33221 but did not respond to HTTP.
`start_backend` timed out after 120s with a confusing error. The
agent had to manually discover the stale PID via
`Get-NetTCPConnection`, kill it, and retry.

**Manual backend startup doesn't work for ingest.** Starting
`runHeadlessEval` directly via Gradle produces a backend where the
Worker is not connected to any watched folder. Direct `/api/ingest`
POST does nothing. Only jseval's `--start-backend` path (which adds
a watched root after health check) produces a working ingest
pipeline. This is not documented anywhere.

**Full eval is too slow for field verification.** The smallest
corpus (scifact) has 5184 docs and takes ~5 min for full enrichment.
Verifying "does this new field show up with non-zero values?" should
take 30 seconds, not 5 minutes.

### Proposed fixes

- **A. Document the verification command** in the agent guide
  Verification Workflow section.
- **B. Port conflict detection in `start_backend`** — probe port
  before spawning; kill stale processes; raise clear errors for
  live conflicts. ~20 line change in `jseval/backend.py`.
- **C. `jseval smoke` subcommand** — ingest 10-20 docs, wait for
  one enrichment batch, assert non-zero fields, exit. Target < 60s.

## Stale Gradle daemon re-spawns backend (session: 343)

### Complaints

**`Stop-Process -Name java` doesn't kill the backend permanently.**
The Gradle daemon respawns after being killed and relaunches the
`runHeadlessEval` task. The agent enters a loop: kill java → jseval
starts → connects to stale backend (uptime 600s+, 0 docs) → confused
→ kill again. This happened 5-6 times in one session, wasting ~90 min.

**`--start-backend --clean` doesn't detect stale backends.** jseval's
`start_backend` checks if the port is free, starts Gradle, and waits
for `/api/health`. But if a stale backend responds on the port between
the free-check and the Gradle spawn, jseval connects to it and proceeds
with 0 indexed documents.

**Background task output not captured.** When jseval runs as a
background task, the output file stays at 0 bytes for the entire run.
The agent can't monitor progress and resorts to polling `/api/status`
in tight loops, burning context window on identical responses.

### Root cause

The Gradle daemon (`--no-daemon` is not used) keeps the `runHeadlessEval`
task alive across daemon restarts. `./gradlew.bat --stop` must be called
before `--start-backend` to ensure a clean slate.

### Proposed fixes

- **D. `--stop` Gradle daemon before starting.** In `jseval/backend.py`,
  run `gradlew.bat --stop` before spawning a new backend. ~5 line change.
- **E. Port ownership verification.** After health check, verify uptime
  < 30s and `indexedDocuments == 0`. If uptime is stale, kill the PID
  on the port and retry.
- **F. Log `--start-backend` PID chain.** Log the Gradle PID, Head PID,
  and Worker PID so stale processes can be identified.

## jseval `--max-queries` scoring denominator bug (session: 343)

### Complaint

`--max-queries N` evaluates N queries but `ir_measures.calc_aggregate`
receives the full qrels (300 queries for SciFact). The 290 un-queried
queries get nDCG=0, dragging the aggregate down by N/300. Result:
`--max-queries 10` reports nDCG=0.019 instead of the correct 0.596.

The agent ran 4 diagnostic runs, compared per-query files, checked
qrels, and investigated a phantom "BM25 regression" that didn't exist.
~30 min wasted.

### Fix (applied this session)

Filter qrels to evaluated queries before calling `scoring.evaluate()`:
```python
eval_qrels = {qid: qrels[qid] for qid in query_texts if qid in qrels}
```
One-line fix in `jseval/run.py:194`.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Agent friction log with explicit "Fix (applied this session)" entries and concrete one-line fix. Log-doc role complete; fixes recorded.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

