# Claude Code Instructions for JustSearch Sandbox Validation

You are running inside a **Windows Sandbox** — an ephemeral, clean Windows
environment with no development tools, no source code, and no pre-existing
models. Your job is to validate a JustSearch **release candidate** end-to-end on
a clean machine and report where a first-time user would be confused, blocked,
misled, or scared.

## Read these first (per-round authority)

Three staged files govern this round. Read them before launching JustSearch:

1. **`coverage-brief.md`** — the generated, per-candidate list of the surfaces
   this release *must* exercise, derived from what the candidate actually ships
   (endpoints, UI surfaces, interaction rungs) plus the claims it publishes. This
   is the authority for *what to cover*; it cannot silently omit a newly-shipped
   surface. If a surface is on it and you cannot reach it, that is a finding.
2. **`validation-mode.md`** — the model mode for this instance (`fresh-install`
   vs `pre-staged-models`). Overrides any static wording about host models.
3. **`sandbox-environment.md`** — directory layout, what is staged, environment
   characteristics.

The final validation summary must state the mode explicitly and report coverage
against `coverage-brief.md`.

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

Cover every `sandbox`-tier item in `coverage-brief.md`, or record why an item was
not reachable. Items marked "covered elsewhere (host tier)" are verified by a host
test and need only a reachability spot-check here; items marked exempt are not
Sandbox-validated. At finalize, run the coverage check (see *Coverage & evidence*)
so an untouched required surface fails the round rather than being forgotten.

## The `/mcp` product endpoint (verify it — it is the point of this release line)

JustSearch serves a production **MCP endpoint** at `POST /mcp` (loopback), the
"private retrieval backend for agents" the README advertises. This is a *product*
surface, distinct from the developer MCP dev-tools that are absent in the sandbox.
Verify a real external MCP client can reach it on a clean install using the
official MCP Inspector CLI (MIT, run via `npx`, nothing to vendor):

```powershell
npx @modelcontextprotocol/inspector --cli "http://127.0.0.1:<port>/mcp" --transport http --method tools/list
```

Expect the tool set to come back. If `npx`/node is not installed, record that MCP
was not exercised and why (a gap to close), rather than skipping silently.
Protocol conformance itself is owned by a host integration test; the Sandbox's
unique job is proving clean-install reachability and discoverability.

## What's available

- **Mapped folder** at `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\` —
  contains the JustSearch installer, this CLAUDE.md, `coverage-brief.md`,
  `validation-mode.md`, `docs/`, `.claude/`, `collect-evidence.ps1`, and a
  `tools/` directory for installers staged from the host.
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

## Required validation phases

1. **Installer launch and security prompts** — run the installer from the mapped
   folder. Capture any SmartScreen / Defender / Smart App Control /
   unsigned-publisher UI, any failure to honour `/S`, and the exact action needed
   to continue. (These Windows-trust prompts cannot be CI-gated — they are the
   Sandbox's unique responsibility; see the must-watch items in `coverage-brief.md`.)
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
    the target is lifecycle, persistence, port binding, or wireup.
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

Key API endpoints (no auth needed, `prod=false`):

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/health` | GET | Lifecycle state |
| `/api/status` | GET | Full system status |
| `/api/knowledge/search` | POST | Search (`{"query":"...","limit":5}`) |
| `/api/knowledge/ingest` | POST | Ingest (`{"paths":["..."]}` — directory inputs return `scanId`) |
| `/api/knowledge/status` | GET | Index/enrichment progress |
| `/api/ai/install/start` | POST | Start model download (`{"acceptTerms":true}`) |
| `/api/ai/install/status` | GET | Download progress. Top-level `state: "completed"` does NOT mean all packages installed; check `installedFully: true` and per-package `state`. |
| `/api/ai/runtime/status` | GET | Per-feature runtime status (NVML VRAM, ONNX `modelActive` flags) |
| `/api/inference/status` | GET | LLM runtime state |
| `POST /mcp` | POST | **Production MCP endpoint** (Streamable HTTP) — the agent-facing retrieval backend. Verify via the Inspector CLI, not raw curl. |
| `/api/mcp/token` | GET | MCP session-token issuance |

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

Record each round's findings and their routing decision in this candidate's
**convergence tempdoc** (`docs/tempdocs/NNN-<version>-sandbox-convergence.md`);
the durable "how" stays in `cut-a-release.md`, which does not accrete per release.

## Writing results

Files written to the mapped folder
(`C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest`) persist on the host after
the sandbox closes. Anywhere else (`C:\`, the user profile) is wiped on shutdown.
Report findings by journey with screenshot filenames and raw API/log evidence, and
state the coverage result against `coverage-brief.md`.
