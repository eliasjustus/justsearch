---
title: Installer Issues
type: reference
status: stable
updated: 2026-04-07
description: "Code signing, sandbox testing, SAC workarounds, Worker spawn, GPU detection, ORT sessions."
---

# Installer Issues

Issues related to packaging, installation, and sandbox testing.

**Key Files:**
- `modules/shell/src-tauri/src/lib.rs`
- `scripts/sandbox/sandbox-launch.py`
- `scripts/ci/package-installer-win.ps1`
- `scripts/ci/sign-windows.ps1`
- `scripts/ci/verify-windows-signature.ps1`

---

## Open Issues

### INS-001: Windows Sandbox SAC Blocks Unsigned Installers
- **Severity:** P2
- **Status:** open (workaround applied)
- **Found:** 2026-01-23
- **Component:** Windows Sandbox environment

**Description:** Windows Sandbox has Smart App Control (SAC) enabled by design, which blocks unsigned executables including development installer builds. The Windows Security settings UI is greyed out inside Sandbox ("managed by your administrator").

**Impact:**
- Cannot test unsigned installers in Windows Sandbox
- SAC popup blocks installation with "Smart App Control has blocked this app"
- No official Microsoft workaround exists

**Workaround Applied:**
The sandbox launcher script now attempts to disable SAC at startup via registry + CiTool:

```powershell
# In the sandbox harness (scripts/sandbox/sandbox-launch.py)
$sacDisableCmd = @(
  'reg add "HKLM\SYSTEM\CurrentControlSet\Control\CI\Policy" /v VerifiedAndReputablePolicyState /t REG_DWORD /d 0 /f',
  'CiTool.exe -r'
) -join ' &amp; '
```

**Note:** This workaround is not officially supported by Microsoft and may not work reliably on all systems. User testing on 2026-01-23 confirmed it works on their machine.

**Alternative Solutions:**
1. **VirtualBox + Windows 10** - No SAC exists in Windows 10
2. **VirtualBox + Windows 11** - Manually disable SAC after installation, take snapshot
3. **Sign the installer** - Code signing certificate (~$300-500/year) for production builds

**Recommendation:**
- For development: Use the workaround or VirtualBox
- For production: Implement code signing

---

### INS-002: Worker spawn fails on installed app (G27)
- **Severity:** P1/Critical
- **Status:** fixed
- **Found:** 2026-04-06
- **Component:** `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerConfig.java`

**Description:** `resolveWorkerLibDir()` was gated on `isProd=true`; Tauri passes `isProd=false` in the alpha build, causing the production resolution path (`libDir.resolve("worker")`) to be skipped entirely. The Worker falls through to the Gradle dev layout path which does not exist in the installed app.

**Fix:** `resolveWorkerLibDir()` now checks the bundled layout (`lib/worker/`) unconditionally as a fallback, regardless of `isProd`.

**Secondary issue (open):** Tauri `resource_dir()` returns `\\?\` extended-length paths; `Files.isDirectory()` fails on these paths. Needs normalization in `lib.rs`.

---

### INS-003: nvidia-smi polling spam (G28)
- **Severity:** P3/Low
- **Status:** fixed
- **Found:** 2026-04-06
- **Component:** `modules/ai-backend/src/main/java/io/justsearch/aibackend/gpu/VramDetector.java`

**Description:** `VramDetector` respawns `nvidia-smi` every poll cycle (~5-10s) when it is not installed, because the failure result is not cached. Each poll logs a DEBUG-level `CreateProcess error=2` exception.

**Fix:** Sticky `nvidiaSmiUnavailable` flag caches the failure permanently. Resets on explicit `invalidateCache()`.

---

### INS-004: ORT FP16 session hang on CPU (G29)
- **Severity:** P1/High
- **Status:** partially fixed
- **Found:** 2026-04-06
- **Component:** `modules/ort-common/src/main/java/io/justsearch/ortcommon/OnnxSessionCache.java`

**Description:** ORT `EXTENDED_OPT` with FP16 models on CPU inserts thousands of Cast (FP16->FP32) nodes, then runs extensive single-threaded graph rewriting. This can take 30-60+ minutes, appearing as a hang. On CPU-only machines, users who Install AI will never get enrichment.

**Fix:** Changed to `BASIC_OPT` for FP16 models on CPU. Deeper fix (ship FP32 CPU models or pre-optimize offline) tracked in tempdoc 376.

**Impact:** On CPU-only machines (no CUDA), enrichment (embedding, SPLADE, NER, chunks) is blocked. Search works in TEXT-only mode but hybrid/semantic search is unavailable.

---

### INS-005: Install AI aborts on first asset failure (G30)
- **Severity:** P2/Medium
- **Status:** open
- **Found:** 2026-04-06
- **Component:** `modules/ui/src/main/java/.../AiInstallService.java`

**Description:** The download pipeline aborts remaining assets after the first failure. For example, if the FP16 reranker download fails (404 or curl exit 22), subsequent assets like `citation-config.json` are left in `pending` state. 23/24 assets succeed but the overall state reports `failed`.

**Impact:** Users see a failure status despite having all critical models downloaded. Non-critical asset failures prevent completion of remaining downloads.

**Workaround:** Re-running Install AI will retry failed/pending assets.

---

### INS-006: Rust build broken — tokio time feature missing (G18)
- **Severity:** P1/Critical
- **Status:** uncommitted fix exists
- **Found:** 2026-04-01
- **Component:** `modules/shell/src-tauri/src/lib.rs`, `modules/shell/src-tauri/Cargo.toml`

**Description:** `lib.rs` uses `tokio::time::timeout` but `Cargo.toml` lacks the `"time"` feature on the tokio dependency. No installer can be built from current `main`.

**Fix:** Add `"time"` to tokio features in `Cargo.toml`.

---

### INS-007: NSIS stale temp files cause build error (G19)
- **Severity:** P2/Medium
- **Status:** open (workaround exists)
- **Found:** 2026-04-01
- **Component:** `modules/shell/src-tauri/target/release/nsis/`

**Description:** `target/release/nsis/` retains corrupted temp files across builds, causing persistent `Internal compiler error #12345` during NSIS compilation.

**Workaround:** `rm -rf target/release/nsis/` before build.

**Impact:** Build failures that persist across retries until the stale directory is manually cleaned.
