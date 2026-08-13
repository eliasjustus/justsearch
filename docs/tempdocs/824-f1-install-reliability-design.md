---
status: active
created: 2026-08-12
updated: 2026-08-12
---
# F1 — Install AI reliability + honest terminal state (round 16 diagnosis + fix design)

Wave worker C, 2026-08-12. Investigation + design only; no product code written.
All `file:line` citations are against
`F:\justsearch-public\.claude\worktrees\agent-af8fe60ae5840189a\` (branch
`worktree-agent-af8fe60ae5840189a`, base `d39b4c69`), which matches `main` for every
file cited.

---

## 0. Executive summary

Round 16's install failures are **one environmental trigger multiplied by five product
defects**, none of which is the one the round hypothesised.

* The environment (Windows Sandbox → `github.com` release CDN) intermittently killed
  **new** connections: BITS reported `TransientError: The connection with the server was
  terminated abnormally`, curl reported exit 52 (empty reply) / 35 (TLS).
* The product converts that transient into a permanent, user-visible failure because
  **every asset gets exactly one BITS attempt plus one curl attempt, ~7 s apart, with no
  application-level retry** — and I proved locally that the product's curl argument list
  **does not retry exit 52 at all** (1 attempt, 3 ms).
* The round's own characterisation is partly wrong: **BITS did not fail 100 %.** The
  product log shows 38 fetches, 17 BITS failures (45 %), 21 BITS successes. What is
  striking is the *conditional*: when BITS failed, the curl fallback fired ≤ 0.8 s later
  and failed **14 / 17 = 82 %** of the time. The two attempts land inside the same
  seconds-long degraded window, so the "fallback" adds almost no independent chance.
* `splade/config.json` is an **optional metadata file** (`ModelCapabilityResolver` reads
  it opportunistically and degrades with a warning; `SpladeModelDiscovery.REQUIRED_FILES`
  does not list it). Its absence produced a full-strength "A required component is
  missing" while SPLADE was demonstrably loaded and running on CUDA. The completeness
  model has **no required/optional axis** and no reconciliation with observed runtime
  reality.
* INS-005 as written in `docs/explanation/12-desktop-installer-and-sandbox-setup.md`
  §6.1.1 ("aborts remaining assets after the first failure") **is already fixed** — the
  loop `continue`s per asset and the round-16 log proves it. That doc paragraph is stale
  and must be corrected as part of this work (`retire-with-a-sweep`).
* **F4**: `/api/ai/install/repair` is absent from `docs/reference/api-contract-map.md`
  (grep: zero hits for any `/api/ai/install/*` route). The empty-400-body claim is
  **OPEN** — the code path demonstrably writes a typed JSON body, and the single trace
  span shows a 0.76 ms 400 (i.e. `TERMS_REQUIRED`, thrown before any work), which is
  exactly the shape PowerShell 5.1's `Invoke-RestMethod` discards. A discriminating
  re-probe is specified in §5.

---

## 1. Evidence base (measured, not asserted)

### 1.1 What the product's own log says

Source: `F:\justsearch-public\tmp\sandbox\share\evidence\logs\headless-backend.log.2`
(the install activity is entirely in `.log.2`; `.log` and `.log.1` contain no download
lines).

| Signal | Count | How counted |
|---|---|---|
| `Resume decision for …` (= one `ResumableFetch.fetch` per asset attempt) | **38** | `grep -c "Resume decision"` |
| `BITS download failed; falling back to curl.exe: BITS failed (TransientError): The connection with the server was terminated abnormally` | **17** | `grep -c "BITS download failed"` |
| `curl.exe failed with exit code 52` | **13** | `grep -o … \| uniq -c` |
| `curl.exe failed with exit code 35` | **1** | same |
| `Package install failed [pkg]: Download failed for …` | **14** | `grep -o … \| sort \| uniq -c` |

Derived, and load-bearing for the design:

* **BITS failure rate = 17/38 = 45 %**, not 100 %. `DownloadExecutor.download`
  (`modules/app-services/…/install/DownloadExecutor.java:68-78`) always tries BITS first
  on Windows and always logs on failure, so 21 assets were transferred *by BITS*. The
  round's "BITS service Stopped/StartupType Manual → 100 % failure" reading is **refuted
  by the product's own log**. (A `Manual` service that BITS cmdlets start on demand is
  consistent with what the log shows.)
* **curl-after-BITS-failure failure rate = 14/17 = 82 %.** Only 3 of the 17 fallbacks
  succeeded.
* 14 failures / 38 attempts = 37 % overall asset-attempt failure rate.
* Every failing asset failed **at connection setup, not mid-transfer**: e.g. splade
  `model_fp16.onnx` (497 MB) failed 8 s after its resume decision (log line 349), which
  is BITS' ~6 s poll budget plus curl's <0.8 s.
* Every failing URL is on the same host:
  `https://github.com/eliasjustus/justsearch-releases/releases/download/models-v1/…`
  (`modules/ui/src/main/resources/ai/model-registry.v2.json`).

### 1.2 Per-asset failure tally (all runs in the log)

```
3  splade      splade/naver-splade-v3/config.json
2  ner         onnx/ner/model_fp16.onnx
2  citation    onnx/citation-scorer/tokenizer.json
1  splade      splade/naver-splade-v3/{model_fp16.onnx, tokenizer.json, idf.json}
1  reranker    onnx/reranker/model_fp16.onnx
1  embedding   onnx/gte-multilingual-base/{pooling_config.json, model_manifest.json}
1  citation    onnx/citation-scorer/config.json
```

Note `splade/vocab.txt` (871 891 B) **succeeded via curl at 20:18:22**, 8 s before
`idf.json` failed via curl at 20:18:30 — failure is not a function of file size or of the
URL, it is a function of *when the connection was opened*.

### 1.3 Local probe I ran (discriminating, reproducible)

A socket server that accepts and closes without replying (`scratchpad/empty_reply_server.py`)
reproduces exit 52 deterministically.

```
# product's exact argument list (DownloadExecutor.java:276-285)
curl.exe --fail --location --retry 3 --retry-delay 2 --continue-at - --output out.bin URL
  → curl: (52) Empty reply from server ; EXIT=52 ; time_total = 0.0034 s ; ONE connection

# same, plus --retry-all-errors
curl.exe --fail --location --retry 3 --retry-delay 1 --retry-all-errors --continue-at - … URL
  → four "curl: (52)" lines ; EXIT=52 ; FOUR connections
```

**Conclusion (proven, not inferred): `--retry 3` covers only curl's "transient" set
(timeouts, 408/429/5xx, and with `--retry-connrefused`, connection-refused). Exit 52 and
exit 35 are outside it, so in round 16 curl made exactly one attempt per asset and
returned in milliseconds.** The manual fetch that returned HTTP 200 in 0.41 s was, in
effect, *the retry the product never made*.

### 1.4 Hypotheses actively refuted

| Hypothesis | Refuted by |
|---|---|
| Stale resume record wedged `config.json` | Product log: `Resume decision for config.json.partial: FRESH (no partial bytes on disk)` — and `DownloadResume.clear` (`DownloadResume.java:95-102`) deletes both partial and sidecar on FRESH |
| `--continue-at -` sent a bogus `Range` | Partial absent ⇒ offset 0 ⇒ no `Range` header; also failures are pre-response (52/35), not 416/33 |
| Registry SHA/size wrong for that asset | Failure text is `Download failed for …` (the transfer branch, `ResumableFetch.java:147-148`), not `Verification failed: …` (`:162`) |
| Parallel fetches tripping rate limiting | The download loop is strictly sequential (`AiInstallService.java:609-698`); one `DownloadExecutor`, one asset at a time |
| INS-005 abort-remaining still present | Log shows splade continuing to `tokenizer.json`, `vocab.txt`, `idf.json`, `config.json` after `model_fp16.onnx` failed; code `continue`s at `AiInstallService.java:667` |
| BITS failed 100 % / service Stopped | 21 of 38 fetches produced no BITS-failure line |

---

## 2. Verified root-cause chain, per symptom

### S1 — "5 of 7 packages FAILED on the first run"

**Chain (verified).** Environment resets ~40 % of new connections to the release CDN in
bursts → `DownloadExecutor.download` gets one BITS attempt; on `TransientError` it throws
out of `awaitBitsJob` (`DownloadExecutor.java:237-242`) and is caught at `:73-75` → one
curl attempt fires ≤ 0.8 s later, inside the same degraded window, and does not retry
(§1.3) → `ResumableFetch` returns `ok=false` (`ResumableFetch.java:141-148`) →
`failPackage(...)` + `continue` (`AiInstallService.java:666-667`) → package terminal-failed
for the run (`updatePackageState` refuses to leave `failed`,
`AiInstallService.java:1391-1393`).

With a 37 % per-asset failure rate and ~24 assets in the GPU_FULL plan, 5 of 7 packages
containing at least one failed asset is the expected outcome, not an anomaly.

**INS-005 status: fixed, doc stale.** Per-asset isolation exists. What the round saw as
"pending items left behind" in the mid-flight snapshot
(`api-install-status-5of7-failed.json`: `chat: downloading`, `cuda-runtime: pending`) is
simply the sequential loop not having reached them yet — the later snapshot shows both
`installed`.

### S2 — "the product's fetch fails where manual curl succeeds"

**Root cause (verified, §1.3):** it is not a header/TLS/proxy difference. It is
**attempt count and timing**. The product makes 2 correlated attempts inside ~7 s; the
manual probe made 1 attempt at an uncorrelated moment. Contributing defects:

* **D1** no application-level retry/backoff anywhere in `ResumableFetch.fetch` — the
  two-pass loop at `ResumableFetch.java:126` is a *verification* retry (resumed bytes
  failed SHA), not a transport retry.
* **D2** curl's `--retry` set excludes 52/35 (`DownloadExecutor.java:276-285`).
* **D3** BITS' own transient-retry machinery is discarded: `awaitBitsJob` throws on the
  *first* poll that observes `TransientError` (`:237-242`). BITS is designed to retry a
  transient error on its own `RetryDelay`/`RetryTimeout` schedule; the product never lets
  it.
* **D4** the fallback is issued immediately, maximising correlation with the failure that
  triggered it (this is what the 82 % conditional measures).
* **D5 (diagnosability)** curl's merged stdout+stderr is drained into
  `OutputStream.nullOutputStream()` (`DownloadExecutor.java:290-299`), so the only
  surviving evidence is the numeric exit code in a log line — the API surfaces nothing.

### S3 — "`splade/config.json` wedges permanently across repairs"

**Partially OPEN — but no asset-specific mechanism is needed to explain it.**

* Nothing in the state machine treats it specially. By the final repair, the planner had
  narrowed splade to exactly this one file (`api-api-ai-install-status.json`:
  `splade.bytesTotal = 872`, every other package `bytesTotal = 0`), i.e. **repair asset
  selection works correctly**.
* Each repair = one BITS attempt + one curl attempt. Under the measured conditional
  (82 % per fallback), 4 consecutive failures has probability ≈ 0.45 — unremarkable.
* It is the **last file of the splade package** in registry order
  (`model-registry.v2.json:62-105`), so its outcome alone decides the package's terminal
  state on every repair pass where the rest already succeeded.
* Refuted alternatives are listed in §1.4.

**Discriminating experiment (round 17 must run it, since I cannot):** at the moment of a
wedge, run **N = 20** sequential `curl.exe -v` attempts against the exact URL with the
product's exact flags, spaced 3 s. Failure rate ≈ 40-80 % ⇒ purely stochastic, design in
§3 closes it. Failure rate 100 % while a plain `curl URL` succeeds ⇒ an asset-specific
factor exists and the `-v` transcript will name it (Range header, HTTP/2 negotiation,
redirect target). Capture the `-v` transcript either way — round 16 has no packet-level
evidence at all.

### S4 — dishonest terminal state

Three separate authorities, and the UI reads the wrong one.

1. **Top-level `state`** — `applyCompletionState` (`AiInstallService.java:755-797`)
   deliberately sets `completed` even with failures, with a counting message
   ("AI installed (6/7 packages; 1 failed)"). This is *already honest by design* and
   should not change.
2. **`installedFully`** — `countPackagesByState("installed") == total`
   (`:763`); after a restart it is instead re-derived from disk by
   `InstallCompleteness.installedFully()` (`InstallCompleteness.java:147-152`), gated on
   `"idle".equals(status.state)` (`AiInstallService.java:341`) — so **it is never
   recomputed within the session that ran the install**.
3. **`repairNeeded`** — `failedCount > 0` after a run (`:794`), or
   `InstallCompleteness.repairNeeded()` = "ANY registry-required file for this profile is
   missing" (`InstallCompleteness.java:170-172`) after a restart.

The UI headline comes from `repairNeeded` **alone**:
`modules/ui-web/src/shell-v0/views/BrainSurface.ts:1316-1325` →
`'Installed — repair available'` / `'A required component is missing — use Repair in
Advanced.'`, and `:2319-2328` in the Advanced panel.

**The defect:** `repairNeeded` is a claim about *bytes on disk versus the registry*, and it
is asserted with the vocabulary of *capability loss* ("a required component"), with no
reference to whether the capability actually works. Round 16's machine had:

* `/api/ai/runtime/status` → `splade: {status: "active", modelActive: true,
  executionProvider: "cuda"}` (`evidence/api-api-ai-runtime-status.json`) and 1 660 real
  ORT inference calls;
* the only missing file being `splade/config.json`, which **no required-file list names**:
  `SpladeModelDiscovery` REQUIRED_FILES = `{model.onnx, tokenizer.json, vocab.txt}`
  (`modules/worker-core/…/splade/SpladeModelDiscovery.java:13,18`). The file *is* read
  opportunistically by `ModelCapabilityResolver` (`modules/ort-common/…/ModelCapabilityResolver.java:284,331`)
  for `max_position_embeddings` / `hidden_size`, but every read is a
  `readIntField(...)`-with-warning fallback, never a hard requirement.

So the registry has **no required/optional axis**, and completeness is byte-equality
against the registry file list. An 872-byte optional metadata file therefore produces the
same terminal red as a missing 500 MB model.

Note the repo already contains the *correct* pattern one screen away:
`shouldHintRepairForGpuFallback` (`BrainSurface.ts:239-245`) requires **both** a
bookkeeping signal (`repairNeeded`) **and** an observed runtime consequence
(`gpuFallback`). The headline copy simply does not use it.

This is the mirror image of `unreachable-seed-green` (tempdoc 817 round-15 F1): there,
bookkeeping asserted green over a red reality; here, bookkeeping asserts red over a green
reality. Same root: *the claim does not name its authority, and no authority is
reconciled against observation.*

### S5 (F4) — `POST /api/ai/install/repair` 400 with empty body

* Route: `modules/ui/src/main/java/io/justsearch/ui/api/routes/AiRoutes.java:107`
  → `AiInstallController.handleRepair` (`AiInstallController.java:124-144`).
* Body parsing: `parseAcceptTerms` (`:146-156`) tolerates any body and defaults to
  `false`; `service.repair(acceptTerms)` delegates straight to `startInstall`
  (`AiInstallService.java:526-528`), which throws
  `AiInstallException(400, TERMS_REQUIRED, "You must accept the model terms before
  downloading.")` at `:458-461`.
* The catch writes `ctx.status(400).json(ApiErrorHandler.toResponse(...))` — a
  `Map` with `error`, `errorCode`, `errorClass`, `retryable`, optional `requestId`
  (`ApiErrorHandler.java:92-95`, `:108-112`).
* No global filter blanks it: `ApiSecurityFilters` emits only 401/403/503 (grep of
  `status(` in that file), and the `HttpResponseException` mapper only re-applies the
  status without touching the body (`LocalApiServer.java:397-399`).
* **The round made exactly one such call**, and the trace records it:
  `evidence/traces.ndjson` → `http.post./api/ai/install/repair`, `duration_ms: 0.7591`,
  `http.status_code: "400"`. Sub-millisecond ⇒ it is the `TERMS_REQUIRED` throw, i.e. the
  round POSTed without `{"acceptTerms": true}`.
* **OPEN:** whether the wire body was really empty. The most likely explanation is the
  client: PowerShell 5.1 `Invoke-RestMethod` throws on non-2xx and the response stream is
  routinely already consumed/disposed by the time `$_.Exception.Response` is read — a trap
  the sandbox's own `CLAUDE.md` documents in the abstract. The server code path writes a
  body.
* **Verified regardless:** `/api/ai/install/repair` — and in fact **every**
  `/api/ai/install/*` route — is absent from `docs/reference/api-contract-map.md` (grep for
  `install/repair|install/start|install/cancel|install/status|TERMS_REQUIRED`: zero hits).
  A caller genuinely has no way to learn the shape.

---

## 3. Fix design

Ordered by "how much of round 16 it would have prevented".

### 3.1 Bounded, spaced, per-asset transport retry (closes S1, S2, S3)

Add a retry policy **around the transport, inside `ResumableFetch.fetch`** — the existing
`Transfer` seam (`ResumableFetch.java:28-49`) is already the injection point tests use.

* Attempts per asset: **4** (1 + 3 retries). Delays **3 s, 9 s, 27 s** with ±30 % jitter.
  Spacing is the load-bearing part: the measured failures are *time-correlated*, so
  re-attempting 0.8 s later buys almost nothing while re-attempting 27 s later is close to
  an independent trial. Worst case per permanently-dead asset: ~40 s + transport time.
* **Do not retry deterministic failures.** curl exit 22 (`--fail`, i.e. HTTP 4xx) and a
  SHA/size verification failure are not transport flakiness — retrying a 404 for 40 s is
  pure latency. Retry only: BITS non-`Error` failures, curl 52/35/7/28/56/18, and
  `CURL_LAUNCH_FAILED`.
* Cancellation must remain honoured *between* attempts (`cancelRequested.getAsBoolean()`
  before each), and the retry must not defeat the resume-verification pass: each attempt
  re-enters the existing decide → transfer → verify cycle.
* Escalate transport across attempts rather than repeating the identical one:
  attempt 1 = BITS→curl (today's behaviour); attempt 2 = curl only (skip the 6 s BITS
  budget); attempt 3 = curl only with `--http1.1`; attempt 4 = curl only. This costs
  nothing and covers the "HTTP/2 negotiation" branch of S3's open question for free.

### 3.2 Make each transport attempt actually try (closes S2's D2/D3/D5)

`DownloadExecutor.runCurl` (`:274-325`) argument list gains:

```
--retry-all-errors            # proven necessary in §1.3
--retry-connrefused
--connect-timeout 20
--speed-limit 1024 --speed-time 60   # kill a stalled-but-open transfer
--user-agent JustSearch/<version>
```

and stops discarding diagnostics: keep the **last ~2 KB** of curl's merged output in a
ring buffer instead of `nullOutputStream()`, and attach it (plus the exit code) to the
per-file failure record. Round 16's investigator had to read the head log to learn even
the exit code; the API should carry it.

`awaitBitsJob` (`:217-257`): treat `TransientError` as **non-terminal**. Keep polling while
BITS retries, bounded by a wall-clock deadline (e.g. 60 s without `BytesTransferred`
progress) and by `snap.ErrorCount`; only `Error`/`Cancelled` are immediately fatal.
Also pass `-RetryInterval 60 -RetryTimeout 300` on `Start-BitsTransfer`
(`:384-400`) so BITS' own schedule is explicit rather than inherited.

*Risk to name:* a too-generous BITS deadline turns a dead network into a very long install.
The deadline must be a hard cap, and the phase message must show which attempt is running
("Downloading X — attempt 2 of 4").

### 3.3 Terminal-state truthfulness (closes S4)

Three changes, smallest-first:

**(a) Required vs optional in the registry.** Add `"required": true|false` (default
`true`) to `supportingFiles` entries in `model-registry.v2.json` — **both copies**
(`modules/ui/src/main/resources/ai/…` and `modules/configuration/src/test/resources/ai/…`).
Classify from the actual consumers: `model.onnx`/`model_fp16.onnx`, `tokenizer.json`,
`vocab.txt` = required (`SpladeModelDiscovery:18`); `idf.json` = required (consumed by
`SpladeIdfQueryEncoder:45`); `config.json`, `pooling_config.json`, `model_manifest.json` =
optional metadata (`ModelCapabilityResolver` reads them with warning-fallbacks). Anything
not classifiable stays required — fail closed.

**(b) Completeness answers two questions, not one.**
`InstallCompleteness` already classifies per file; extend it to distinguish:

* `installedFully` — no **required** file missing (unchanged semantics for required files,
  so `InstallCompleteness`'s existing contract/registry-addition logic is untouched);
* `repairNeeded` — a **required** file is missing;
* new `optionalGaps: [{packageId, fileName}]` — optional files missing. Surfaced, never
  alarming.

Round 16's machine then reads: `installedFully: true`, `repairNeeded: false`,
`optionalGaps: [splade/config.json]` — which is the truth.

**(c) Reconcile bookkeeping with observed runtime.** The install status must not assert a
capability is broken when the runtime says it is running. Add to `AiInstallStatus` a
per-package `functionalStatus` projected from the same source
`/api/ai/runtime/status` uses (`onnxFeatures[].modelActive` + `status`), and make the FE
headline require **both** halves, exactly as `shouldHintRepairForGpuFallback`
(`BrainSurface.ts:239-245`) already does:

```
show "A required component is missing — use Repair"
  iff repairNeeded (required file missing)
  AND the affected package's capability is NOT observably active
```

When a required file is missing but the capability is active anyway, the honest copy is
"Working, but an expected file is missing — Repair will restore it", and when only
optional files are missing, no repair prompt at all.

**(d) Recompute after a run, not only after a restart.** `maybeRecomputeInstalledFromDisk`
is gated on `"idle".equals(status.state)` (`AiInstallService.java:341`), so an install that
ends `completed` never re-derives from disk in that session. `applyCompletionState` should
finish by re-deriving completeness from disk (the planner probe is cheap: existence +
size, `InstallPlanner.java:289-297`), so the run's own bookkeeping is *checked against
disk* before it becomes the terminal claim. This is the direct anti-`unreachable-seed-green`
move: the completion claim is verified, not asserted.

### 3.4 Repair must converge or say why not (closes S3's user-facing half)

`repair()` is literally `startInstall()` (`AiInstallService.java:526-528`) — identical odds,
no memory. Add:

* **Per-file attempt memory** across repair runs, persisted next to the partial (the
  `DownloadResume` sidecar is the natural home, or a small `install-attempts.json` under
  `homeDir`): `{targetPath, attempts, lastExitCode, lastErrorText, lastAttemptEpochMs}`.
* **Escalation:** repair pass *n* starts at transport tier *n* (§3.1), so a wedged file
  gets a different transport each pass rather than the same one.
* **A terminal, honest verdict.** After **3** consecutive repair passes fail the same file
  with a transport error, the status carries
  `state: "completed"`, the package carries
  `{state: "failed", terminalReason: "TRANSPORT_UNAVAILABLE", attempts: 12,
    lastError: "curl exit 52 (empty reply from server)", url: "<direct URL>"}`,
  and the UI stops offering an unqualified "Repair". It says what happened, how many times,
  and offers the direct URL + target path as a manual fallback. **An affordance that cannot
  succeed must not be presented as the remedy** — that is the actual user-facing defect in
  round 16, independent of the network.
* `repairNeeded` must stay true (a required file *is* missing) while the *remedy* changes.

### 3.5 F4 — endpoint contract + error bodies

**Document** in `docs/reference/api-contract-map.md` the whole family, which is currently
entirely absent:

| Route | Method | Request | Success | Errors |
|---|---|---|---|---|
| `/api/ai/install/manifest` | GET | – | `ModelRegistry` | 500 `MANIFEST_UNAVAILABLE` |
| `/api/ai/install/plan-preview` | GET | – | `InstallPlanPreview` | 500 `MANIFEST_UNAVAILABLE` |
| `/api/ai/install/status` | GET | – | `AiInstallStatus` | – |
| `/api/ai/install/start` | POST | `{"acceptTerms": true}` | `AiInstallStatus` | 400 `TERMS_REQUIRED`, 403 `DOWNLOADS_DISABLED`, 409 `INSTALL_ALREADY_RUNNING`, 500 `INSTALL_START_FAILED` |
| `/api/ai/install/cancel` | POST | – | `AiInstallStatus` | 500 `INSTALL_CANCEL_FAILED` |
| `/api/ai/install/repair` | POST | `{"acceptTerms": true}` | `AiInstallStatus` | same set as `start`, 500 `INSTALL_REPAIR_FAILED` |

Error body shape (all of them):
`{"error": string, "errorCode": string, "errorClass": string, "retryable": boolean,
"requestId"?: string}`.

Also worth stating explicitly in the doc, because it is surprising: **`repair` is `start`**
— it re-plans against disk and downloads only what is missing, and it therefore requires
`acceptTerms` exactly like a first install.

**Close the empty-body ambiguity with a test, not an argument** (§4): a live-stack/TCK
assertion that a `POST` with `{}` returns 400 **with a non-empty body carrying
`errorCode: "TERMS_REQUIRED"`**. If that test passes, the round-16 observation was
client-side and the doc entry is the whole fix; if it fails, there is a real Javalin
body-suppression bug and the test is already the regression home.

---

## 4. Regression tests

**Unit — fault injection at the `Transfer` seam** (`ResumableFetchTest` already drives it):

1. `transfer` fails twice with a retryable code, then succeeds ⇒ `Outcome.ok()` true,
   `transferAttempts == 3`, delays observed via an injected clock. *Fails today.*
2. `transfer` fails with a non-retryable code (curl 22 / HTTP 4xx) ⇒ exactly **one**
   attempt, no backoff spent.
3. Verification failure still discards and restarts from zero exactly once (pin the
   existing `ResumableFetch.java:157-175` behaviour against the new retry loop —
   the two loops must not multiply into 8 transfers).

**Unit — `DownloadExecutor` invocation pinning** (`DownloadExecutorTest` exists):

4. The curl argument list contains `--retry-all-errors` and `--connect-timeout`. A plain
   string assertion; it is the regression home for the §1.3 finding.
5. `awaitBitsJob` given a `TransientError` snapshot followed by `Transferred` returns
   **true** (today it throws on the first `TransientError`).

**Unit — per-asset isolation and honest terminal state** (`AiInstallService*Test`):

6. Plan with 3 packages; injected transport fails package B's only file ⇒ A and C reach
   `installed`, B `failed`, run `state == "completed"`, `installedFully == false`,
   message "2/3 packages; 1 failed". (Pins INS-005-fixed so it cannot regress.)
7. **Wedge scenario:** the only missing file is an *optional* one and the runtime reports
   the capability active ⇒ `repairNeeded == false`, `optionalGaps` names the file,
   `installedFully == true`.
8. Required file missing **and** capability observably active ⇒ `repairNeeded == true`
   but the copy is the "working, but…" variant (FE test).
9. **Repair non-convergence:** three repair passes each failing the same file ⇒ package
   carries `terminalReason: TRANSPORT_UNAVAILABLE`, `attempts >= 3`, a non-empty
   `lastError`, and the direct URL.

**FE (`modules/ui-web`)**: extend `BrainSurface.repairRemedy.test.ts` — "A required
component is missing" must **not** render when the only gaps are optional or when the
affected capability is observably active; and the non-convergent case must render the
manual-fallback copy rather than a Repair button.

**Contract**: TCK/live-stack test for §3.5's 400-with-typed-body, plus a
`check-*`-style assertion that every route registered under `/api/ai/install/` appears in
`api-contract-map.md` (this class of gap is exactly what round 16 hit).

**What round 17 verifies live — by TRIGGERING the failure, not hoping for it.**
A round that merely re-runs Install AI on a good network proves nothing (that is the
`green-masked-destructive` precondition). Required:

* Add a **dev/test-only fault injector** — a sysprop such as
  `-Djustsearch.ai.install.faultInjectPct=40` that makes a configurable fraction of
  transport attempts fail as exit 52, refused in `prod=true` builds — or, if a product
  sysprop is unacceptable, point the install at a loopback proxy that closes 40 % of
  connections by rewriting the registry base URL for the round.
* Assertions in-round: (1) **every** package reaches a terminal state and the loop never
  aborts remaining assets; (2) the log shows spaced retries with escalating transport;
  (3) at ~40 % per-attempt failure the install completes fully (4 spaced attempts make
  per-asset failure < 3 %); (4) with injection at 100 %, the terminal state names the
  failed files, the exit code, and the manual-fallback URL, and after three repairs says
  "cannot repair automatically" instead of looping; (5) turn injection off, click Repair
  once, and the install converges to `installedFully: true`.
* Independently: the §2/S3 **N = 20 `curl -v` probe** if a wedge is reproduced, and the
  §3.5 **`curl.exe -i -X POST …/repair -d '{}'`** raw-bytes capture to settle F4.

---

## 5. Scope estimate and risk

| Work item | Size | Risk |
|---|---|---|
| §3.1 retry policy in `ResumableFetch` (+ tests 1-3) | S-M | Low. Isolated behind an existing seam; main risk is compounding with the verification retry — test 3 pins it |
| §3.2 curl args + stderr capture (+ tests 4) | S | Low |
| §3.2 BITS transient tolerance (+ test 5) | M | **Medium** — a poll loop with a new deadline can hang an install. Needs a hard wall-clock cap and a visible attempt counter |
| §3.3a registry `required` flag (two copies) | S | Low, but the dual-copy sync is a known trap; classification must fail closed |
| §3.3b-c completeness split + runtime reconciliation | M | **Medium** — `installedFully` semantics are load-bearing for tempdoc 804 §B8 / 805 G.3 behaviours; `InstallCompletenessTest` is the guard and must stay green untouched |
| §3.3d recompute-from-disk at completion | S | Low |
| §3.4 repair attempt memory + terminal verdict | M | Medium — new persisted state; must degrade to today's behaviour when the file is unreadable |
| §3.5 docs + contract test | S | Low |
| Correct INS-005 in `docs/explanation/12-…` §6.1.1 | XS | None, and required by `retire-with-a-sweep` |
| FE copy + tests | S | Low |

**Total: roughly 3-4 focused days**, splittable into three independent chunks that can land
separately: (A) transport reliability §3.1+§3.2, (B) truthfulness §3.3+FE, (C) repair
convergence §3.4 + F4 §3.5. (A) alone would have turned round 16 green; (B) alone would
have made round 16's outcome *honest*; both are needed for 0.2.0.

Cross-cutting risks:

* Retries make a genuinely offline install slower to fail. Mitigate with the visible
  attempt counter in the phase message and a per-asset cap.
* Changing `installedFully` risks re-opening round-11's false-negative
  (tempdoc 805 G.3) or round-10's false "Not Installed" (804 §B8). Neither is touched if
  the required/optional axis only ever *removes* optional files from the required set —
  strictly a relaxation on files that were never consumed as requirements.
* The registry change lands in two copies plus a schema; the round-16 candidate's installed
  contracts must keep parsing (`InstallContractIO`), so the new field must be additive with
  a `true` default.

---

## 6. What I could NOT establish

1. **Whether the repair 400's body was genuinely empty on the wire.** The code path writes
   a typed JSON body and no filter blanks it; the trace shows a 0.76 ms 400 consistent with
   `TERMS_REQUIRED`. PowerShell 5.1's `Invoke-RestMethod` discarding the body is the
   likeliest explanation, but I have no packet capture. §3.5's contract test settles it
   either way.
2. **The environmental cause of the connection resets** (Sandbox NAT, host filtering,
   GitHub CDN anti-abuse). Round 16 captured no `curl -v` / no packet-level evidence, and
   I cannot reproduce the sandbox. The fix does not depend on knowing, but round 17 should
   capture it.
3. **Whether the `splade/config.json` wedge is purely stochastic.** The measured 82 %
   conditional makes 4 consecutive failures ordinary (p ≈ 0.45), and every asset-specific
   hypothesis I could test against the log and the code is refuted (§1.4). I could not
   *exclude* an asset-specific factor without the N = 20 probe (§2/S3) — recorded as OPEN,
   not as solved.
4. **The actual BITS service state in that image.** The round asserts Stopped/Manual; the
   product log refutes 100 % BITS failure (21 of 38 fetches succeeded through it). I can
   contradict the "100 %" claim but not report what the service state actually was.
5. **Whether any non-Java consumer reads `splade/config.json`.** I grepped Java main
   sources across `modules/`; a resource-path-based or packaging-side consumer would not
   have shown up. Confidence is high (the runtime demonstrably ran without it) but not
   absolute — hence "optional metadata", not "dead file".
6. **No live verification of any kind.** Per the brief I did not start the shared dev
   stack, so every claim here is source-, log-, and local-probe-based. The §4 tests are
   the conversion of these hypotheses into truth (`audit-without-test`).
