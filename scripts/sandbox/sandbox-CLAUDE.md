# Claude Code Instructions for JustSearch Sandbox Validation

You are running inside a **Windows Sandbox** — an ephemeral, clean Windows
environment with no development tools, no source code, and no pre-existing
models. Your job is to validate a JustSearch **release candidate** end-to-end on
a clean machine and report where a first-time user would be confused, blocked,
misled, or scared.

## Read these first (per-round authority)

Five staged files govern this round. Read them before launching JustSearch:

1. **`coverage-brief.md`** — the generated, per-candidate list of the surfaces
   this release *must* exercise, derived from what the candidate actually ships
   (endpoints, UI surfaces, interaction rungs) plus the claims it publishes. This
   is the authority for *what to cover*; it cannot silently omit a newly-shipped
   surface. If a surface is on it and you cannot reach it, that is a finding.
2. **`validation-mode.md`** — the model mode for this instance (`fresh-install`
   vs `pre-staged-models` vs `upgrade-from-release` vs
   `in-app-update-from-release`). Overrides any static
   wording about host models.
   **Round-mode policy:** a release's FIRST round and its FINAL qualifying round
   MUST run `fresh-install` (the only mode that covers the real download path);
   intermediate convergence rounds MAY use `pre-staged-models` for iteration
   speed, and their evidence must be labeled as such. A release's qualifying set
   must additionally include **at least one `upgrade-from-release` round**
   (install the previous public release, seed minimal data, install the candidate
   over it) — real users arrive from the previous version, not only from a clean
   machine, and the strongest defect repro this harness ever produced came from a
   non-fresh arrival state (tempdoc 734 A.1, round 2). Tempdoc 750 Part C.
   For `in-app-update-from-release`, also follow
   `updater-qualification.md`: the installed source is a previous-source
   Sandbox build with the updater test gate, the target is served from the
   authenticated loopback closed set, and `collect-updater-evidence.ps1`
   captures the durable recovery oracle before interruption and after restart.
3. **`charter.md`** — this round's pre-registration (SBTM's *charter*, adapted —
   see *Retrospective / debrief* below): what the round is FOR, each open
   blocker's needs-round vs. needs-dig classification, the chosen mode and why,
   and expectations. Written before the round runs; the debrief is read against
   it. A round asked to verify something the charter classifies needs-dig should
   flag that, not silently spend itself on it.
   **A watch item that names only a broken signature is under-specified — do not
   file a finding on the signature alone.** Charters must state what the signal
   looks like when the build is HEALTHY as well as when it is broken (the rule
   lives in `docs/how-to/cut-a-release.md` step 2, addressed to whoever writes
   the charter). When one does not, establish the discriminator yourself before
   calling anything a defect, and record the charter's imprecision as a
   harness finding. Worked example (round 8, 2026-07-31): the charter said
   `Combined backfill: docs=N (embed=0,splade=0,chunks=0)` "repeating at high
   frequency … is the livelock; it must not appear." It appeared **143 times,
   six within 142 ms — and was not the livelock**: the lines carried real
   progress, the run terminated on its own, enrichment completed, and a
   60-second idle window afterwards produced zero new lines. Healthy backfill
   emits that exact signature. The defect is **non-termination** — the
   signature still firing while every coverage counter is static and ingest
   jobs are starved. Following that charter literally would have produced a
   false HIGH against a working build.
4. **`sandbox-environment.md`** — directory layout, what is staged, environment
   characteristics.
5. **`staging-gaps.md`** — assets the host failed to stage this round (e.g. the
   SciFact corpus, a Node installer). Each entry must be recorded as a
   round-level coverage gap, not silently absorbed.
6. **This candidate's convergence tempdoc** (`docs/tempdocs/NNN-<version>-sandbox-convergence.md`,
   staged read-only under `docs/`) — states what this round is *for*: which prior
   findings it exists to re-confirm fixed, and which are still open. A tempdoc is
   **dated history, not current truth** — it reflects what was known when it was
   written, not the state of the build you are running. Use it to know what to
   verify, not as a substitute for verifying it: check every claim it makes
   against the running candidate, don't just cite the tempdoc's own words back.

The final validation summary must state the mode explicitly, report coverage
against `coverage-brief.md`, and quote `candidate-provenance.md` (the staged
record of the installer's filename, SHA-256 and — when the build made it
derivable — its commit) so the archived evidence identifies the build it came
from without host-side archaeology afterwards.

## Mission (durable)

Prove the installed candidate works on a clean machine **and** that a first-time
user can understand it during setup and heavy local work. Two layers:

- **Cheap end-to-end sanity ladder** — installer boots, Install AI reaches a
  complete state, the selected runtime activates, one chat completion succeeds,
  one HYBRID/reranker query runs, and state survives a restart. This anchors that
  the core path is intact for *this* candidate. Do not assume a prior candidate's
  pass carries over — a release candidate is, by definition, a materially
  different build (new backend jars, new endpoints, new trust surfaces), so the
  thing that changed is exactly the thing most worth testing.
- **Frontend / trust truthfulness** — after sanity passes, organize by **user
  journey**, not backend subsystem. The highest-value findings are where the UI
  disagrees with reality: a label that says "Ready" while the API says otherwise,
  a mode that misrepresents the response, a trust surface (encryption status,
  memory, skins) that misleads. UI/API disagreement is a finding even when the
  API passes.

**GUI-capture launch requirement.** Surface-tier coverage (screenshots, the
Frontend / trust truthfulness layer above) needs a PNG on disk, not a
computer-use tool — nothing requires the screenshot come from a tool call.

**Claude-for-Chrome (`claude --chrome`) is RESOLVED NEGATIVE — do not attempt
it again.** A verification spike (tempdoc 727-followup smoke round) found it
doubly blocked: (1) the paired Chrome is the operator's **HOST** browser, not
one inside the sandbox — `list_connected_browsers` reports `isLocal: true`
for it regardless, which is a trap, since pairing follows the Claude account
to the host machine, not the sandbox; sandbox loopback (`127.0.0.1:<port>`)
is unreachable from it while a public page like `example.com` loads fine. (2)
An installed build serves **no HTTP SPA at all** — every route probed returns
404 except the API surface, so even a sandbox-local Chrome would have nothing
to point at. Either blocker alone is fatal; both apply. Do not stage or
recommend the Chrome MSI for this purpose again.

**Recommended default: the native PowerShell GUI tier**, staged at
`<mapped folder>\gui\` (`snap.ps1`, `win-capture.ps1`, `click.ps1`,
`crop.ps1`, `gui-approve.ps1` — see `gui/README.md`). It drives the **real**
Tauri WebView2 shell via `System.Drawing.Graphics.CopyFromScreen` capture and
`SendKeys`/`mouse_event` input — proven end-to-end including a full
GUI-driven TYPED_CONFIRM approval (backend-verified: grant issued, docCount
incremented, file searchable). It needs no tool, no extension, no pairing, no
account, and no network, and it caught a HIGH-severity trust-surface finding
(an expired pending authorization presenting a live-looking but dead
Approve/Deny ceremony) that the API tier's clean PASS on the same feature
could not see. Coverage credits the PNGs these scripts write, exactly like any
other screenshot.

Alternative for the future: the tauri-driver/WebView2 path (tempdoc 374 item
4, POC'd) — structured, element-based targeting instead of pixel coordinates,
worth having eventually but not currently blocking anything.

Either way, the Step-0 capability probe (staged as the `/start` skill,
`sandbox-start-SKILL.md`) remains the fail-loud guard — it now checks BOTH a
computer-use tool AND the native-capture path before declaring a round
API-only (see that skill for the amended rule).

Cover every `sandbox`-tier item in `coverage-brief.md`, or record why an item was
not reachable. Items marked "covered elsewhere (host tier)" are verified by a host
test and need only a reachability spot-check here; items marked exempt are not
Sandbox-validated. At finalize, run the coverage check (see *Coverage & evidence*)
so an untouched required surface fails the round rather than being forgotten.

## The `/mcp` product endpoint (verify it — it is the point of this release line)

JustSearch serves a production **MCP endpoint** at `POST /mcp` (loopback), the
"private retrieval backend for agents" the README advertises. This is a *product*
surface, distinct from the developer MCP dev-tools that are absent in the sandbox.
**The documented Inspector CLI reachability check now FAILS on this build** (round
10, tempdoc 734/804): the packaged candidate boots `prod=true` (see *Key API
endpoints* below), `POST /mcp` enforces the session token like every other
mutating route, and a plain `npx @modelcontextprotocol/inspector --cli
"http://127.0.0.1:<port>/mcp" --transport http --method tools/list` 401s with no
`WWW-Authenticate` header — the Inspector CLI infers OAuth from the bare 401 and
demands an interactive TTY it cannot get in a round. **Verify reachability with a
raw `POST /mcp` JSON-RPC `tools/list` call carrying the session token instead**
(fetch it from `GET /api/mcp/token` — see *Key API endpoints* below for the
token-fetch pattern):

```powershell
$port = (Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json).head.apiPort
$token = (Invoke-RestMethod -UseBasicParsing "http://127.0.0.1:$port/api/mcp/token").token
Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$port/mcp" `
  -Headers @{ "X-JustSearch-Session" = $token } -ContentType "application/json" `
  -Body '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Expect the tool set to come back. If you must use the Inspector CLI itself, it has
no flag for a custom auth header on this build, so it is not currently a working
substitute — record the limitation and use the raw-POST workaround above. If
`node` is not installed at all (for the mutating-tool step below), record that MCP
was not exercised and why (a gap to close), rather than skipping silently.
Protocol conformance itself is owned by a host integration test; the Sandbox's
unique job is proving clean-install reachability and discoverability.

**The mutating-tool step needs a different client.** The Inspector CLI's
`--tool-arg` string-coerces every value and cannot express `justsearch_ingest`'s
`paths: string[]` argument, so `tools/call justsearch_ingest` cannot be driven
through it. Use the staged `mcp-client\mcp-typed-confirm.mjs` instead (run as
`node mcp-client\mcp-typed-confirm.mjs --target <path>`; see
`mcp-client\README.md`) — it drives the REAL shipped MCPB stdio bridge
(`mcp-client\index.js`, a verbatim copy of `packaging/mcpb/server/index.js`) to
exercise the TYPED_CONFIRM procedure. See `governance/sandbox-coverage.v1.json`'s
`cohort:mcp` `validateHow` (surfaced in your `coverage-brief.md`) for the exact
required sequence.

## What's available

- **Mapped folder** at `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\` —
  contains the JustSearch installer, this CLAUDE.md, `coverage-brief.md`,
  `validation-mode.md`, `docs/`, `.claude/`, `collect-evidence.ps1`, the
  `gui/` native GUI capture/input harness (see *GUI-capture launch
  requirement* below and `gui/README.md`), and a `tools/` directory for
  installers staged from the host.
- **Models** may be mapped at
  `C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels\` only in
  `pre-staged-models` mode. Read `validation-mode.md`; never set
  `JUSTSEARCH_MODELS_DIR` during a `fresh-install` round.
- **PowerShell** and standard Windows tools; **internet access** (for model
  downloads, Claude OAuth, Git/Chrome installs if not pre-staged in `tools/`).

## What's NOT available

- No source code, no Gradle, no JDK, no Node.js (unless you install it)
- No `jseval`, no JustSearch **dev-tools MCP**, no worktrees, no agent telemetry
  (these are developer tooling — unrelated to the product `/mcp` endpoint above)
- `nvidia-smi.exe` is NOT on PATH in the sandbox (don't use it as a CUDA probe)
- No automatic install — you install Git, Claude Code, and JustSearch yourself.

## GPU characteristics (durable)

Windows Sandbox passes the host's NVIDIA card through for vGPU. The probes that
matter:

- **NVML** (`nvml.dll`, System32): works — reports VRAM + driver version;
  `/api/ai/runtime/status` shows `vramDetectionSource: "nvml"` with a non-zero
  total.
- **`nvcuda.dll`** (CUDA driver API, System32): loadable; `cuInit` +
  `cuDeviceGetCount` succeed. The install gate uses this driver-API probe, so
  chat installs and runs on the bundled cuda12 llama-server variant at GPU speed
  without a system CUDA toolkit.
- **`nvidia-smi.exe`**: NOT on PATH — the legacy shell-out detector returns -1
  here. Use `/api/ai/runtime/status` (NVML-first) for authoritative GPU metadata.

On a low-VRAM host (< the chat package's VRAM floor), Install AI *skips* chat and
the GPU runtime package and everything falls back to CPU — that is expected
behaviour on that profile, not a regression. Installer size and the model /
GPU-runtime package set are candidate-specific and change per release — do not
hard-code figures from memory; the authority for *this* candidate's asset set is
its published `SHA256SUMS` / GitHub Release (the 726 asset pipeline), per
`docs/how-to/cut-a-release.md`.

## Setup (manual)

1. **Git** — run `tools\Git-Setup.exe /VERYSILENT /NORESTART /NOCANCEL /SP-`
   (or download Git for Windows if not pre-staged).
2. **Claude Code** — single command:
   ```powershell
   irm https://claude.ai/install.ps1 | iex; $bin = "$env:USERPROFILE\.local\bin"; $u = [System.Environment]::GetEnvironmentVariable("Path","User"); if ($u -notlike "*$bin*") { [System.Environment]::SetEnvironmentVariable("Path","$u;$bin","User") }; $env:Path += ";$bin"
   ```
   Run `claude` from the mapped folder. The staged `.claude/settings.json` sets
   `permissions.defaultMode = "bypassPermissions"`, so Claude Code starts in
   bypass mode automatically. If ignored, launch with
   `claude --dangerously-skip-permissions`.
3. **JustSearch** — run the `*-setup.exe` in the mapped folder. Per ADR-0024, the
   NSIS installer is **per-user** and lands at `%LOCALAPPDATA%\JustSearch\`, NOT
   `C:\JustSearch\`. User data (downloaded models, index, logs, runtime state)
   lives separately at `%APPDATA%\io.justsearch.shell\`.

## Coverage & evidence (mechanize capture, keep judgment)

Two staged tools make rounds repeatable and make coverage fail closed:

- **`collect-evidence.ps1`** captures the mechanical layer: it discovers the
  backend port from the runtime manifest, hits the API sanity ladder, exercises
  `/mcp` via the Inspector CLI, and saves raw snapshots into `evidence/`. Run it
  early and after each major step. It *captures*; the honesty/scary-UI judgment
  stays with you.
- **Endpoint tracing** — launch JustSearch with `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed`
  so every API request is recorded to `%APPDATA%\io.justsearch.shell\telemetry\traces.ndjson`.
  This is the empirical record of which endpoints the round actually exercised.
  (The `.wsb` launcher sets this env var for you via `setx`; if you launch the app
  another way, set it yourself first. Accepted values: `none` (default, no spans),
  `sample` (1%), `detailed` (all).) `collect-evidence.ps1` **copies** this file into
  the evidence dir so it survives sandbox teardown.
- **Save all evidence under the mapped folder** (`Desktop\JustSearchTest\evidence\`) —
  only that folder persists on the host. Name UI screenshots so the surface is
  identifiable (e.g. `NN-security-panel.png`, `NN-rag-ask-answer.png`); **surface
  coverage is credited from screenshots only** (image files), never from the API-JSON
  snapshots the harness also writes there.
- **At finalize (host-side)**, the round's coverage is asserted by diffing the
  must-touch set against the exercised endpoints + screenshots. Because the sandbox
  has no Python, this runs on the **host** against the persisted evidence dir after the
  round:
  ```
  python scripts/sandbox/check_coverage.py \
    --manifest tmp/sandbox/share/coverage-manifest.json \
    --traces   tmp/sandbox/share/evidence/traces.ndjson \
    --evidence-dir tmp/sandbox/share/evidence
  ```
  An untouched `sandbox`-tier surface is a **blocking finding** (non-zero exit).
  The same check also fails closed if `evidence/retrospective.md` is missing or
  too thin — see *Retrospective* below — and if `evidence/evidence-review.v1.json`
  is missing, omits a credit-eligible screenshot, or reports a mismatch — see
  *Evidence review* below. Filename-token matching proves a screenshot's NAME
  claims a surface; it cannot prove the PIXELS show it, so a reader pass over
  every credited screenshot is a required, separately fail-closed gate, not an
  optional judgment call.
- **Token health (host-side, tempdoc 805 Part G.4)** -- the same `traces.ndjson`
  is also checked for round 11's discriminator: any POST/PUT/DELETE span
  answered 401 in under 5ms is the auth-filter-rejection fingerprint (a
  missing/stale session token reaching the webview), promoted from prose to a
  mechanical, fail-closed gate:
  ```
  python scripts/sandbox/check_token_health.py tmp/sandbox/share/evidence/traces.ndjson
  ```
  Allowlisted: a fast 401 on `/mcp` immediately followed (within 60s) by a 200
  on `/mcp` -- an external MCP client probing once without a token and then
  retrying with one is expected, not a defect. A blocking finding here means a
  webview call fired without (or with a stale) session token during the round.

### Search parity (golden queries)

The Sandbox cannot measure absolute search quality (no jseval here). Instead the round checks
**parity with dev**: the operator generated a per-candidate "golden" expected-results baseline
(`golden-parity.json`) by running the fixed query set (`golden-queries.json`) against the dev
stack on the SAME build + SAME corpus (scifact) this round uses. Your job in-round is only the
capture step, already wired into `collect-evidence.ps1` — if `golden-queries.json` is staged next
to it, the script POSTs each query to `/api/knowledge/search` (hybrid, limit 10) against your
running candidate and saves the raw responses to `evidence/golden/<queryId>.json`. No judgment is
required from you here; the tolerance comparison against the baseline runs host-side at finalize
via `check_golden_parity.py`. If `staging-gaps.md` lists a missing golden-parity baseline for this
candidate, record that as a round-level coverage gap (per the protocol above) rather than
attempting to judge search quality yourself.

Parity is only measurable when the round ran the **same embedding weights** as the baseline —
`check_golden_parity.py` checks this automatically via `embeddingFingerprintCurrent`
(`/api/knowledge/status`), which IS the SHA-256 of the loaded embedding model file (audited
2026-07-14). A `pre-staged-models` round maps the host's models; a `fresh-install` round downloads
them — on GPU these are byte-identical, but a CPU-only round loads FP32 (`model.onnx`) where a
GPU-generated baseline used FP16 (`model_fp16.onnx`), so a CPU round needs its own CPU-generated
baseline, not the GPU one. The finalize check also fails closed if the round's indexed corpus is
far smaller than the baseline's, or if any captured golden response shows `dense-retrieval`
skipped (hybrid silently collapsed to BM25) — both would otherwise surface as a phantom ranking
regression instead of the real cause.

**Calibration provenance (every threshold names the population it was sampled on —
tempdoc 750 P2 "calibration names its axes"; applying a threshold across an unsampled
axis is itself a finding, not a formality):**

- **Same-machine dev-rebuild population** (n=3 clean dev rebuilds, scifact, GPU-FP16,
  same corpus/code/model, 30 query-observations, 2026-07-15): overlap never drops
  below 9/10, top-1 never moves (0/30), only 2 queries ever shift, by exactly one
  doc. Axes held constant: GPU + driver, embedding-inference environment, index
  build environment. This envelope covers **rebuild noise only**.
- **Sandbox↔sandbox population** (round 5 vs round 6, two different builds,
  fingerprint-identical weights, 2026-07-17 — tempdoc 750 §Derisk): all 10 queries
  share 10/10 result docs across the two rounds; per-hit **dense-leg score deltas
  never exceed 1.8e-4**. Embedding inference inside the Sandbox environment is
  highly reproducible; this is the measured basis for the baseline's
  `denseScoreEnvelopeAbs`.
- **Dev↔sandbox (the axis the finalize comparison actually spans) is NOT yet a
  calibrated population.** Rounds 5 and 6 both diverged from the dev baseline
  identically (q04 6/10, q06 5/10, q08 4/10) — a **systematic** dev-vs-sandbox
  difference, not round noise. Its cause is 734 finding 5's open question; the v2
  baseline's per-pair dense-score comparison is the discriminating instrument
  (embedding-output variance vs. HNSW selection variance).

Two earlier readings recorded here are superseded on evidence: "likely
HNSW/approximate tail churn" (refuted by the dev-rebuild calibration — `q08` is
10/10 across every rebuild pair) and the CPU-FP32-vs-GPU-FP16 hypothesis (refuted by
round 6, which ran GPU-FP16 fingerprint-identical to the baseline and still
diverged). The FP16/FP32 note above still matters operationally — a CPU round needs
a CPU-generated baseline — it just is not finding 5's cause. Treat a sub-7 overlap
as a **finding to explain, not** noise to wave through; the check's report now
carries typed reasons and per-leg attribution so the explanation can start
host-side instead of costing another round.

**Policy status (owner decision 2026-07-30, tempdoc 798 B7 / 750 option A4):** a
sub-7 overlap is **DESCRIPTIVE — reported in full, but it does not block the
round**. The floor was calibrated only on the same-machine dev-rebuild
population; a dev to Sandbox comparison crosses a boundary that calibration never
sampled, so a sub-floor overlap there is an uncalibrated measurement rather than a
demonstrated ranking regression. The **blocking** assertion is the
environment-robust one: golden #1 within the captured top-3. This is a demotion of
what the number decides, not of what is measured or reported — explaining a
sub-floor overlap is still expected of the round.

## Required validation phases

1. **Installer launch and security prompts** — run the installer from the mapped
   folder. Capture any SmartScreen / Defender / Smart App Control /
   unsigned-publisher UI, any failure to honour `/S`, and the exact action needed
   to continue. (These Windows-trust prompts cannot be CI-gated — they are the
   Sandbox's unique responsibility; see the must-watch items in `coverage-brief.md`.)
   **The no-admin claim is verified host-side, not by this round.** The
   README's "no admin rights needed" claim is now asserted mechanically by
   `scripts/ci/check-installer-execution-level.mjs`, which checks both the
   source config (`bundle.windows.nsis.installMode: currentUser`, ADR-0024)
   and — when a built installer is available — the BUILT installer's embedded
   Windows manifest (`requestedExecutionLevel="asInvoker"`). The round does
   not need to prove this. Your one residual job: **if any elevation prompt
   appears during the JustSearch install, that is a finding** — note the
   publisher shown and report it.
   **Run this round non-elevated.** UAC renders on the secure desktop, so
   screenshots can't capture it, and an already-elevated session is never
   re-prompted (an elevated process tree cannot observe a UAC prompt at all,
   regardless of what the installer requests). But that is not the reason to
   run non-elevated: an elevated round does not reproduce a normal user's
   environment and can mask permission defects a real user would hit — a pass
   that depends on an environment precondition is not a pass (this repo's
   `green-masked-destructive` principle). If this session's own terminal is
   already elevated (`collect-evidence.ps1` self-checks this at Step 0 and
   writes `evidence/elevation-check.txt`), say so in the round's summary —
   an elevation prompt was structurally impossible to observe this round.
2. **First app launch** — does `%LOCALAPPDATA%\JustSearch\JustSearch.exe` start
   and render? Save `evidence/NN-first-paint.png`.
3. **Backend health** — read the runtime manifest, then save raw `/api/health`,
   `/api/status`, `/api/ai/runtime/status`, and `/api/inference/status` snapshots
   (`collect-evidence.ps1` does this).
4. **Install-dir hygiene** — run the jar-uniqueness check from `/start` before
   trusting runtime behaviour.
5. **Pre-Install-AI UI sanity** — help search, Library surface, Health/System,
   Brain/Install AI, status badges, console errors.
6. **Install AI** — in `fresh-install` mode, validate the full model + GPU-runtime
   download through the UI, backed by `/api/ai/install/status` snapshots. In
   `pre-staged-models` mode, label the evidence shortcut-only.
7. **Library / indexing journey** — add a folder through the UI where possible;
   for the full corpus, ingest `Desktop\JustSearchTest\scifact\`. Verify
   per-folder row state, Tasks panel live updates, and `/api/knowledge/status`
   agree.
8. **Search / Chat / Brain / Health UI** — run real searches from the UI and
   compare rendered results with API responses. Walk the escalation ladder
   (Search → Documents → Structured → Agent); each rung's label/affordance must be
   honest, and AI-requiring rungs must disable with a clear reason when AI is
   offline (not a dead click).
9. **`/mcp` external-client check** — see *The `/mcp` product endpoint* above.
10. **Trust surfaces** — Security & Privacy (encryption-status truthfulness + the
    irreversible chat-passphrase flow + a recovery key), Memory (inspect/forget;
    empty state reads private-by-default), and Appearance/Skins (apply a built-in
    skin, use the editor, import a skin JSON — never leave an illegible/broken
    surface; the choice survives restart). These map to the privacy/threat-model
    claims the release publishes, so a misleading surface here is high-severity.
11. **Restart cycles** — one cold restart for a frontend-focused round; three when
    the target is lifecycle, persistence, port binding, or wireup. A restart means
    killing ALL FOUR processes — the Tauri shell (`JustSearch.exe`), Head
    (`javaw.exe` under `resources\headless\`), Worker (a second `java.exe`), and
    `llama-server.exe` if active. Closing only the shell window reconnects to the
    same still-running backend and proves nothing; verify a genuine restart by the
    runtime manifest's `instanceId`/`pid` changing. Filter kills by process **Path**
    (`*\JustSearch\*` / `*io.justsearch.shell*`), not bare `ProcessName` — a bare
    `java`/`llama` pattern can kill unrelated processes.
12. **Uninstall** — run only after all evidence is saved, unless told to defer.
    Verify program files are removed and user-data behaviour matches ADR-0024.

## How to test

The backend binds to `127.0.0.1` on a port disclosed two ways:

1. **Read the runtime manifest** (canonical, fastest; tempdoc 501):
   ```powershell
   (Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json).head.apiPort
   ```
2. Fallback if the file is missing:
   ```powershell
   Get-NetTCPConnection -State Listen | Where-Object { $_.LocalAddress -eq '127.0.0.1' }
   ```

**Manifest field paths.** `instanceId`, `pid`, and `lifecycle` are **top-level**
in `runtime/manifest.json`, NOT under `.head` — only `apiPort`, `apiBaseUrl`,
and `readyAt` are nested under `.head`. The restart check in phase 11 above
reads `instanceId`/`pid` directly off the manifest root (`$j.instanceId`, not
`$j.head.pid`, which reads empty).

**PowerShell 5.1 hides API errors unless you ask.** Always pass
`-UseBasicParsing` to `Invoke-WebRequest`/`Invoke-RestMethod` — without it a
call can silently write a 0-byte evidence file instead of failing loud.
Separately, `Invoke-RestMethod` throws on any non-2xx response and by default
**discards the response body** — a JSON error like `{"error":"missing
pendingId"}` is invisible unless you catch the exception and read
`$_.Exception.Response`. A prior round burned 15 minutes brute-forcing
request shapes against a server that was telling it exactly what was wrong
the whole time. Wrap mutating calls in `try/catch` and print the body on
failure before concluding the endpoint is broken.

**The packaged candidate boots `prod=true` — every mutating call needs the session
token.** This is NOT the dev stack: a Sandbox candidate is the SHIPPED package,
and `ApiSecurityFilters` enforces the session token on every `POST`/`PUT`/`DELETE`
globally, with no path exemption (it applies to `/mcp` too). `GET`/`OPTIONS` need
no token. Fetch the token once from `GET /api/mcp/token` (itself unauthenticated by
design — the desktop UI/shipped MCPB bridge use exactly this pattern) and attach it
as the `X-JustSearch-Session` header on every mutating call; omit it and you get a
`401` with `{"error":"Missing or invalid session token","errorCode":"UI_TOKEN_REQUIRED"}`,
not the endpoint's normal response. Worked example:

```powershell
$port = (Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json).head.apiPort
$token = (Invoke-RestMethod -UseBasicParsing "http://127.0.0.1:$port/api/mcp/token").token
$headers = @{ "X-JustSearch-Session" = $token }
Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$port/api/knowledge/search" `
  -Headers $headers -ContentType "application/json" -Body '{"query":"test","limit":5}'
```

Key API endpoints (`GET` needs no token; every other method needs the
`X-JustSearch-Session` header above):

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/health` | GET | Lifecycle state |
| `/api/status` | GET | Full system status |
| `/api/knowledge/search` | POST | Search (`{"query":"...","limit":5}`) |
| `/api/knowledge/ingest` | POST | Ingest (`{"paths":["..."]}` — directory inputs return `scanId`) |
| `/api/knowledge/status` | GET | Index/enrichment progress |
| `/api/indexing/roots` | POST | Add a folder to the library (`{"path":"...","collection"?:"..."}` — `path` must be an existing directory; 400 names the offending field) |
| `/api/chat/ask` | POST | RAG Q&A (`{"question":"..."}` — NOT `query`). **Response is an SSE stream, not JSON** — see below. |
| `/api/ai/install/start` | POST | Start model download (`{"acceptTerms":true}`) |
| `/api/ai/install/status` | GET | Download progress. Top-level `state: "completed"` does NOT mean all packages installed; check `installedFully: true` and per-package `state`. |
| `/api/ai/runtime/status` | GET | Per-feature runtime status (NVML VRAM, ONNX `modelActive` flags) |
| `/api/inference/status` | GET | LLM runtime state |
| `POST /mcp` | POST | **Production MCP endpoint** (Streamable HTTP) — the agent-facing retrieval backend. Needs the session token like any other POST; see *The `/mcp` product endpoint* above for the current verification procedure. |
| `/api/mcp/token` | GET | Session-token issuance (unauthenticated by design — this is how a legitimate client gets the token in the first place) |

Full body shapes for every endpoint live in `docs/reference/api-contract-map.md` (staged under `docs/`).

**SSE requires the `Accept` header.** `GET /api/advisory/authorization-pending/stream`
called WITHOUT an `Accept: text/event-stream` header returns `200` with empty
`text/plain` body and closes immediately — indistinguishable from "no pendings
outstanding." Send the header to get the real `text/event-stream` response
(a `snapshot` frame plus live `UPDATE` frames). A host-side fix/test for the
server's silent-200 behavior is tracked separately (see the SSE observation
note filed against this finding).

**`/api/chat/ask` is a Server-Sent Events stream, not a JSON endpoint** — the
controller initializes SSE response headers before writing anything
(`ChatController.java`, its class doc + `initSseHeaders` call sites). Piping
the raw response through `ConvertFrom-Json` looks exactly like an empty
answer; it is actually a stream of frames. Consume it as SSE, not as a single
JSON body.

**Search hits key on `id`, not `path`.** `results[]` in a
`/api/knowledge/search` response is `id`, `score`, `fields` (a metadata map),
plus optional `matchedFields`/`matchSpans`/`excerptRegions` — there is no
`path` field. Guessing `.path` returns empty and looks exactly like "never
indexed." The full shape is already documented and staged at
`docs/reference/api-contract-map.md` (search under "Current hit shape in
`results[]`") — read it before asserting anything about a search response,
don't guess field names.

**Queue visibility** — `worker.core.pendingJobs` in `/api/status` is the FULL queue
depth (PENDING+PROCESSING) despite its name. `worker.migration.pendingJobsCount` in
`/api/knowledge/status` is PENDING-only and can read 0 while a job is stuck in
PROCESSING — always check its sibling `processingJobsCount` (same payload, also in
`/api/debug/state`) before concluding the queue is idle.

**A 401 renders as zero results in any client that doesn't check status.** The
packaged candidate boots `prod=true` (see *Key API endpoints* above); a `POST`
issued without the `X-JustSearch-Session` header 401s, and a client that only
inspects the parsed response shape — not the HTTP status code — sees
`{"error":"...","errorCode":"UI_TOKEN_REQUIRED"}` shaped exactly like an empty
result set. Round 10 nearly filed a catastrophic false HIGH finding ("the
upgrade emptied the index") for exactly this reason: a post-upgrade oracle run
read `hits: 0` on every query purely because its POSTs were 401ing, and only
the visible `UI_TOKEN_REQUIRED` error body caught it before the round wrote
the finding. Before concluding an index is empty or a search found nothing,
check the HTTP status code and error body first — never trust an empty-looking
result shape on its own.

**Absence of signal is not evidence of absence.** A wrong field name, a genuine
negative result, a silent no-op click, and a surface that never backfills all
render as "empty" — and they are indistinguishable from each other until you
check which one you're looking at. Before filing any negative finding ("X not
indexed", "Y not shown", "Z never fired"), confirm you are reading the right
field/endpoint/surface at all — ideally by first proving the positive control
works (a query you know should return hits, a WARN you know already fired).
The four traps above (hidden error bodies, the SSE-vs-JSON mismatch, `id` vs
`path`, and a 401 rendering as zero results) are concrete instances of this;
treat it as the general case, not just those four.

## Named diagnostic techniques

### Renamed-aside data dir

Used twice (round 9 and round 10, tempdoc 734) to reclassify a blocker as
**build-level** (reproduces on a pristine install, nothing to do with the
upgrade) vs **upgrade-specific** (only reproduces against carried-over user
state): stop all four processes (see *Restart cycles* above — the Tauri shell,
Head `javaw.exe`, Worker `java.exe`, and `llama-server.exe` if active), rename
`%APPDATA%\io.justsearch.shell` aside (e.g. to `io.justsearch.shell.bak` —
rename, do not delete, so the original round's data is recoverable), relaunch
the candidate against the now-pristine (non-existent) data dir, and re-test the
failing behaviour. If it still reproduces against a fresh data dir, the defect
is build-level, not an upgrade artifact — restore the renamed-aside directory
afterwards to resume the original round on its real data. This is what turned
round 10's F7 ("upgrade defect?") into "shipped-UI blocker, reproduced on a
pristine data dir" — that round's single highest-value diagnostic act.

### Verify-the-active-surface-before-clicking (GUI capture)

The DEFAULT loop for every GUI action, not an occasional precaution: **capture**
a fresh screenshot → **crop** the tab-strip/header region (`crop.ps1`, see
`gui/README.md`) so you can read which surface is actually active without
burning context on the full image → **confirm** the active surface matches
what you expect → **click**. Pixel-coordinate automation has no notion of "did
my click land on the surface I think is showing," and a screenshot taken even
one step earlier can be stale by the time the click fires — round 10 lost four
captures to exactly this coordinate drift (a click landed on the wrong tab
because the active surface had moved since the last capture). Do not click from
a screenshot older than the immediately-preceding capture, and do not treat a
zero exit code as proof the click landed on the intended surface — confirm from
the crop, then re-capture afterward to verify the action registered (`click.ps1`
already fails closed on a foreground-focus mismatch; `Assert-AppSurface` in
`gui/README.md` is the API-side confirmation for the same problem).

## Convergence — every finding gets a regression home

The release loop is build → verify → fix → rebuild at the same candidate number →
converge to zero findings (see `docs/how-to/cut-a-release.md`). For the loop to
converge instead of re-finding the same class, every confirmed finding must get a
regression home — **exactly one of**:

- a **gate/test in its natural tier** — backend/API regression → a host unit or
  live-stack test; UI-truthfulness → a ui-shot / RAIL step assertion; or
- a **`sandbox-must-watch` entry** in `governance/sandbox-coverage.v1.json` — for
  findings that cannot be CI-gated (Windows-trust prompts, clean-environment
  timing). These are re-injected into every future `coverage-brief.md`.

A **HIGH-severity finding must get a deliberate second reproduction under varied
conditions** (e.g. fresh install vs. warm reinstall) before the round closes — one
observation is a report, two varied reproductions are evidence. Same discipline as
rule 17 in the `/start` skill (verify a fix by triggering its condition), applied
to findings.

Record each round's findings and their routing decision in this candidate's
**convergence tempdoc** (`docs/tempdocs/NNN-<version>-sandbox-convergence.md`);
the durable "how" stays in `cut-a-release.md`, which does not accrete per release.

## Writing results

Files written to the mapped folder
(`C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest`) persist on the host after
the sandbox closes. Anywhere else (`C:\`, the user profile) is wiped on shutdown.
Report findings by journey with screenshot filenames and raw API/log evidence, and
state the coverage result against `coverage-brief.md`.

### Evidence review (required — a reader, not just a filename, must confirm coverage)

This was measured, not assumed: known-bad artefacts planted into a copy of a
real round's evidence showed `check_coverage.py`'s own filename-token match
(`check_surface`/`check_shape`) credits a **mislabeled capture** (right bytes,
wrong claim — e.g. a command-palette screenshot named/credited as the logs
surface) **0 times out of 4**, while three independent blind readers caught it
4/4, 4/4, 4/4. Four such plants alone flip a correct FAIL into a clean exit-0 —
every gap gets "credited" by a screenshot of something else. No content hash
catches this; only a reader who looks at the pixels can.

**The rule: a capture must EVIDENCE the claimed surface/shape.** A filename is
a claim; the pixels are the evidence. An honestly-named blank capture (a file
genuinely named `-blank` that genuinely is blank) still does **not** evidence
the surface it's filed under — an honest name for a non-evidencing capture is
still not coverage.

Before finalize, open every credit-eligible screenshot (every image file at or
above the size floor `check_coverage.py` enforces — see `MIN_SCREENSHOT_BYTES`)
and write `evidence/evidence-review.v1.json`
(schema: `scripts/sandbox/evidence-review.schema.json`):

- **`examined`** — every screenshot you actually opened, by filename. This is
  the coverage assertion's enumerable list: the finalize check fails closed if
  ANY credit-eligible screenshot in the evidence dir is missing from it. Do not
  pad this list with files you did not look at, and if you run out of budget
  partway through, leave the un-opened files OUT of it and report a partial
  review — a truncated list that silently reads as "reviewed, no issues" on
  the files it never opened is exactly the failure this file exists to close.
- **`mismatches`** — any screenshot whose filename claims something the pixels
  do not support: `{file, claims, shows}`. Any non-empty `mismatches` fails the
  round closed, no matter how the rest of coverage reads — a review that finds
  a lie and passes anyway is decoration.
- **`uncertain`** — screenshots you could not confidently confirm or refute
  (occluded, ambiguous crop): `{file, reason}`. Non-blocking, but report it —
  do not resolve a genuine doubt into a false mismatch or a false clean pass.

**Sharding**: ~90 images is near one agent's practical review budget in a
single pass. On a round with more evidence than that, shard the review across
multiple passes/agents and **reconcile into one `evidence-review.v1.json`**
before finalize (union the `examined` lists, concatenate `mismatches` and
`uncertain`) — do not finalize on an unreconciled partial shard.

`evidence/evidence-review.v1.json` is checked at finalize (see *Coverage &
evidence* above) and the round **fails closed** if it is missing, malformed,
omits a credit-eligible screenshot, or reports a mismatch.

### Retrospective / debrief (required — the loop only improves via this channel)

Every round must write `evidence/retrospective.md`. This is not an optional
afterthought: a prior round's spontaneous "Part B" harness/process retrospective
drove roughly 15 harness fixes, and a later round that skipped one produced zero —
not because nothing went wrong, but because nothing asked for it. The harness only
gets better if every round leaves this behind, on purpose, every time.

This artifact is the round's *debrief* in the Session-Based Test Management sense
(J. Bach & J. Bach, STQE, 2000 — the charter/session/debrief/time-accounting
vocabulary here is adopted from SBTM, adapted to agent-driven rounds, not
invented). Read it against `charter.md`: what the charter asked vs. what happened.

**Time accounting (required section, TBS-adapted).** Include a section headed
`## Time accounting` breaking the round's hours into: **setup**, **install-wait**,
**coverage work**, **findings investigation**, and **write-up**, plus the split of
**on-charter vs. on-opportunity** work (off-charter pursuit of something important
you stumbled into is sanctioned — measure it, don't hide it). Estimates are fine;
the point is that "where do a round's hours go" stops being unanswerable
(tempdoc 734 D.9 asked; nothing before this measured it). The finalize check also
prints a report-only timeline computed from evidence-file timestamps as an
independent cross-check of your self-report.

Cover, at minimum, four things:

- **What the harness/docs got WRONG or made impossible** — a documented procedure
  you could not follow as written (wrong field path, an API call that doesn't do
  what the doc claims, a tool flag that can't express what's needed).
- **What you had to work around or build yourself** to get the job done, and why
  the documented path didn't work.
- **What slowed you down** — friction, ambiguity, a missing staged asset, a dead
  end that cost real time.
- **What you would change** — the concrete fix, not just the complaint.

`evidence/retrospective.md` is checked at finalize (see *Coverage & evidence*
above) and the round **fails closed** if it is missing or too thin to be a real
retrospective — a stub file does not satisfy this.

## Independence invariant (durable — do not "streamline" this away)

The round's self-report and the host-side mechanical re-run at finalize are two
deliberately separate authorities, and they stay separate. The verifier (you)
never sees or edits its own coverage bookkeeping's verdict; the host re-runs
`check_coverage.py` / `check_golden_parity.py` against the persisted evidence and
the two are compared. This pairing is the workflow's best-performing control: in
both GUI-capable rounds to date the mechanical re-run surfaced something the
round's own narrative missed (round 5: mislabeled screenshots and a surface with
zero genuine evidence; round 6: a cohort tested against the wrong port, 26/28 not
the self-reported 9/9 clean). Any future change that merges the self-report with
the finalize check, lets the round grade its own coverage, or drops the
independent re-run "because the round already reports it" removes that control —
if it is ever done, it must be done as an explicit, argued decision, not as
streamlining. (Tempdoc 750 Part E; lineage: 734 Part D.5-D.6/E.9 — the round is
the one tier that can be wrong without failing.)
