---
title: "Tauri Desktop Shell Gaps"
status: done
---

# Tauri Desktop Shell Gaps

## Problem

`modules/shell` has functional backend-spawning, graceful shutdown, and single-instance enforcement, but no desktop integration features beyond those basics. The Tauri shell is the user's primary interface on desktop — missing tray, window persistence, and updater support mean it doesn't feel like a native desktop app.

This document covers both near-term gaps (Tier 1-3) and long-term theoretical improvements for turning JustSearch into a polished, native-feeling desktop application.

## Audit Findings (2026-02-17)

### Tauri Version & Stack

- **Tauri core**: **2.10.2** (upgraded 2026-02-17 from 2.9.3; Cargo.toml pin normalized to `"2"`)
- **Features enabled**: `tray-icon`, `devtools`
- **Toolchain**: stable Rust, target `x86_64-pc-windows-msvc`
- **Bundler**: NSIS only (per-user install), code signing configured
- **Window**: Frameless, transparent, 1024x700, min 600x400, `visible: false` (window-state plugin shows after restore)

### Installed Plugins

| Plugin | Version | Actually Used? |
|--------|---------|---------------|
| `tauri-plugin-single-instance` | 2.4.0 | Yes — prevents duplicate instances, focuses existing window |
| `tauri-plugin-opener` | 2.5.3 | Yes — file opening, reveal-in-explorer |
| `tauri-plugin-dialog` | 2.6.0 | Yes — file/folder pickers in Library and Brain views |
| `tauri-plugin-window-state` | 2.4.1 | Yes — saves/restores window position, size, maximized state (added 2026-02-17) |
| `tauri-plugin-autostart` | 2.5.1 | Yes — launch on login, toggle in Settings > Desktop (added 2026-02-17) |
| `tauri-plugin-notification` | 2.3.3 | Yes — desktop notifications for indexing events (added 2026-02-17) |

### Tauri Commands Exposed (lib.rs)

| Command | Purpose |
|---------|---------|
| `api_port()` | Returns backend port (30s poll timeout) |
| `session_token()` | Returns session auth token (10s poll) |
| `smoke_run_id()` | Testing: returns env var |
| `get_file_metadata(path)` | Returns `{ isDir }` for drag-and-drop directory detection. Path-validated (no traversal/UNC). |
| `open_file(path)` | Open with system handler. Path-validated; also blocks executable extensions. |
| `reveal_in_explorer(path)` | Show in Explorer. Path-validated (no traversal/UNC). |
| `prepare_delete_data()` | Phase 1: generate single-use UUID token for factory reset |
| `confirm_delete_data(token)` | Phase 2: validate token, write reset marker, exit |
| `justsearch_paths()` | Returns model/log/llama-server paths |

### Capability ACL (default.json)

42 permissions in a single monolithic capability file, including full tray operations, `window-state:default`, `autostart:default`, and `notification:*`. Tray is active (2026-02-17). Duplicate `core:resources:default` and unused `global-shortcut:*` were removed.

### Known Bugs

- ~~**Ghost command**: Frontend `useDragDrop.ts:210` calls `invoke('get_file_metadata', { path })` but this command doesn't exist in lib.rs.~~ **Fixed 2026-02-17**: Added `get_file_metadata` Tauri command returning `{ isDir: bool }`.
- ~~**Unused plugin**: `tauri-plugin-global-shortcut` was installed and ACL-configured but never invoked.~~ **Removed 2026-02-18**: Cargo dependency and ACL permissions stripped. Will be re-added when the global search bar (Section A) is implemented.

### Tauri Version Upgrade

JustSearch is on Tauri **2.10.2** (upgraded 2026-02-17). All version-gated features are now available:

- **`removeUnusedCommands`** (Section E2): Enabled 2026-02-18 in tauri.conf.json.
- **`emit_str` / `emit_to_str`** (Tauri >= 2.3): Available. Bypasses serde double-serialization — useful for Java backend JSON passthrough.
- **NVDA screen reader bug** (Section H1): Present in current builds (introduced in 2.3.0, still unfixed upstream). See Section H1 for mitigation.
- **CVE-2025-31477** (CVSS 9.3): Critical scope bypass in `tauri-plugin-shell` <= 2.2.0. JustSearch does not use this plugin, but the CVE illustrates the importance of staying current.
- **Windows IPC performance**: Tauri IPC on Windows has known overhead (~200ms for 10MB payloads vs ~5ms on macOS). Relevant if the shell takes on more data proxying.

See "Version Upgrade Investigation" section at end for detailed findings.

---

## Near-Term Gap Analysis

### 1. System Tray — ~~HIGH impact, LOW effort~~ IMPLEMENTED

**Status**: done

### 2. Window State Persistence — ~~HIGH impact, LOW effort~~ IMPLEMENTED

**Status**: Implemented 2026-02-17. Added `tauri-plugin-window-state` dependency, registered plugin in `setup()`, set `visible: false` in tauri.conf.json (plugin shows window after restoring saved position).

**Remaining caveat**: Known multi-monitor issues — does not remember which monitor (plugins-workspace #1282), window may open off-screen if saved monitor is disconnected. See Long-Term Section I for custom solution.

### 3. Auto-Updater — HIGH impact, MEDIUM effort

**Current state**: Not implemented. Code signing for NSIS installer exists (`tauri.signing.conf.json`), but updater signing keys are separate. No `tauri-plugin-updater`.

**What it enables**: In-app update checks, download progress, and automatic install. Without it, users must manually discover and download new versions.

**Plugin**: `tauri-plugin-updater` (stable). Requires: (1) generate signing keypair, (2) configure endpoint in tauri.conf.json, (3) host update manifest JSON, (4) build with `TAURI_SIGNING_PRIVATE_KEY` set.

**Critical**: Losing the private signing key means existing users can never receive updates.

**Effort**: Medium. Needs key management, a hosted update manifest (GitHub Releases works), CI pipeline changes to produce signed artifacts, and frontend UI for update prompts.

### 4. Splash / Loading Gap — MEDIUM impact, MEDIUM effort

**Current state**: Frontend renders `<LoadingState message="Connecting to backend..." />` while waiting for the API port. But there's a dead gap between Tauri launch and first React render where the user sees nothing (or a brief white flash on the transparent frameless window).

**Tauri pattern**: Create a second lightweight window (`splashscreen`) shown immediately, main window hidden. Rust `setup()` closes splash and shows main once backend port is discovered. No plugin needed.

**Effort**: Medium. Needs a simple HTML splash page, second window in tauri.conf.json, and coordination logic in lib.rs (which already has the port-discovery polling loop).

### 5. Auto-Launch on Startup — ~~MEDIUM impact, LOW effort~~ IMPLEMENTED

**Status**: Implemented 2026-02-17. Added `tauri-plugin-autostart`, registered in lib.rs, toggle in Settings > Desktop section (Tauri-only). Uses `HKCU\...\Run` registry key on Windows.

### 6. Native Notifications — ~~MEDIUM impact, LOW effort~~ IMPLEMENTED

**Status**: Implemented 2026-02-17. Added `tauri-plugin-notification`, utility at `utils/notify.ts` (`sendDesktopNotification`). Wired to Library view via a transition-based `useEffect` that fires when all roots leave `indexing` status while `document.hidden` (window minimized to tray). Permission is auto-requested on first use.

**Remaining**: ~~Wire more notification triggers (agent task done, errors while minimized).~~ **Done 2026-02-18**: `useBackgroundNotifications` hook in App.tsx fires notifications for agent task completion and errors while `document.hidden`. Interactive toast actions (buttons in notifications) are mobile-only — for advanced Windows toasts consider `tauri-winrt-notification` crate.

### 7. Deep Linking — LOW daily-driver impact

**Plugin**: `tauri-plugin-deep-link` v2.4.5. Registers `justsearch://` protocol. On Windows, spawns new process — needs single-instance plugin (already installed) with `deep-link` feature for proper forwarding.

**Use case**: External tools could invoke `justsearch://search?q=foo`. Low priority until there's an ecosystem to integrate with.

### 8. Enable Content Security Policy — ~~HIGH impact (security), LOW effort~~ IMPLEMENTED

**Status**: Implemented 2026-02-17. Set restrictive CSP in tauri.conf.json. See Long-Term Section B1 for the policy that was applied and the rationale.

**Remaining**: Should be tested against a production build (`tauri build`) to confirm no breakage under the stricter policy. Dev mode uses Vite HMR which may need `'unsafe-eval'` — this must NOT be in the production CSP.

### 9. File Association — LOW impact for a search tool

**Built into bundler**: `bundle.fileAssociations` in tauri.conf.json. Registers at install time.

**Use case**: Right-click a file → "Search with JustSearch" context menu. Marginal value — JustSearch searches *collections* of files, not individual files. Would need thought on what opening a single file means in context.

### Cleanup

- ~~`tauri-plugin-global-shortcut` was unused~~ **Removed 2026-02-18**. Re-add when global search bar (Section A) is built.
- ~~Fix the phantom `get_file_metadata` command call in `useDragDrop.ts`~~ **Fixed 2026-02-17**
- ~~`@ts-ignore` in `useDragDrop.ts:212` for untyped invoke result~~ **Fixed 2026-02-18**: Typed as `invoke<{ isDir: boolean }>`

---

## Long-Term Improvements

### A. Global Search Bar (Spotlight/Raycast Pattern)

**Concept**: A global hotkey (e.g., `Ctrl+Shift+J` or `Alt+Space`) summons a small, always-on-top, frameless search input centered on screen. Type a query, see results inline, press Enter to open. Dismiss with Escape or focus-loss.

**Why it matters**: This is the defining UX pattern for search apps. Raycast, Alfred, PowerToys Run, Everything, Wox, and Flow Launcher all use it. Without it, JustSearch requires the user to find and focus the main window — friction that kills frequent usage.

**Tauri v2 feasibility**: Fully supported via multi-window + global shortcut.

- Create a dedicated `quicksearch` window: `decorations: false`, `transparent: true`, `always_on_top: true`, `skip_taskbar: true`, `visible: false` initially, narrow (600x60, expanding with results)
- Register global shortcut via `tauri-plugin-global-shortcut` (already installed but unused — this is its intended purpose)
- On hotkey: show window, focus. On Escape/blur: hide window
- Each window gets its own capability scope (minimal: just search invoke + api_port)
- `tauri-plugin-positioner` can help position it (center screen, near tray, etc.)

**Windows-specific**: Focus stealing prevention. The global hotkey handler runs in Tauri's process context with foreground permission (user just pressed the key), so `SetForegroundWindow` should work. For "return focus to previous app" on dismiss: capture `GetForegroundWindow()` before showing, restore after hiding.

**Effort**: Medium-high. Needs: second window entry point in React, separate capability file, Rust hotkey registration, show/hide logic, focus management. But the payoff is transformative.

**Industry validation**: Raycast launched on Windows (Nov 2025), validating the global search bar pattern on that platform. Wox v2 (open source) ships an MCP server, allowing AI agents to invoke search. Both use mode-prefixed search syntax (e.g., `/files`, `/ai`, `>commands`) to switch search domains without leaving the bar. JustSearch's existing SIMPLE/LUCENE syntax toggle could map to this pattern.

**Additional UX patterns from desktop search apps** (potential enhancements for the search bar or main UI):
- **Quick Look / preview pane**: macOS Finder-style preview on selection (spacebar or hover). JustSearch already has a preview panel in Advanced mode; the search bar could surface a compact version.
- **Usage-based ranking**: Boost results the user frequently opens. Requires a lightweight frequency store (could use `tauri-plugin-store`).
- **Clipboard history integration**: Auto-search from clipboard content. Low effort with `tauri-plugin-clipboard-manager`.

### B. Security Hardening

#### B1. Content Security Policy — IMPLEMENTED

**Status**: CSP enabled in tauri.conf.json as of 2026-02-17. Previously `"csp": null` (completely disabled).

**Applied production CSP** (in tauri.conf.json):
```
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
img-src 'self' asset: http://asset.localhost blob: data:;
font-src 'self' data:;
connect-src 'self' ipc: http://ipc.localhost http://127.0.0.1:*;
media-src 'self'
```

Tauri auto-injects nonces for bundled scripts at compile time. `'unsafe-inline'` for `style-src` may be needed for Tailwind CSS / Vite runtime style injection (the project uses Tailwind utility classes, not CSS-in-JS). Dev mode with Vite HMR needs `'unsafe-eval'` in `script-src` but this must NOT be in production. Test the CSP against the actual build to confirm which directives are required.

**Note**: `worker-src` is omitted — the frontend does not use Web Workers or Service Workers. If workers are added in the future, add `worker-src 'self' blob:`.

**Remaining**: Needs testing against a production build (`tauri build`) to confirm the React app works under the stricter policy.

#### B2. Isolation Mode — NOT VIABLE (Windows + ESM)

Tauri's Isolation pattern injects a sandboxed iframe between the frontend and Tauri Core. All IPC messages are routed through it and encrypted with AES-GCM (per-session keys). The isolation hook can inspect/modify/reject IPC calls.

**Why it matters for JustSearch**: The app renders search results from indexed documents (untrusted content). While the current `escapeHTML` pipeline mitigates XSS (see B5), if a future regression introduced an unescaped path, an attacker could invoke `open_file(path)` to launch malicious executables or `request_delete_data()` for destructive factory reset. (Note: `session_token()` theft is lower risk — the token only works on loopback, and an attacker with XSS already has loopback access.) Isolation mode provides defense-in-depth against this class of regression.

**BLOCKER**: Isolation mode does not work with ES Module frontends on Windows. The isolation iframe's sandboxed origin breaks `import()` / ESM resolution in WebView2. JustSearch uses Vite + ESM — enabling isolation mode would break the entire frontend. This is a known limitation in Tauri v2 with no upstream fix. See: tauri-apps/tauri#8618.

**Alternative defense-in-depth** (implemented 2026-02-18): Harden the IPC surface directly:
1. ~~Validate `open_file`, `reveal_in_explorer`, `get_file_metadata` paths in Rust~~ **Done**: `validate_user_path()` rejects traversal (`..`), UNC paths (`\\\\`), and executable extensions (`.exe`, `.bat`, `.cmd`, `.ps1`, etc.) for `open_file`.
2. ~~Rate-limit destructive commands (`request_delete_data`) with confirmation tokens~~ **Done 2026-02-18**: Two-phase IPC — `prepare_delete_data()` generates a UUID token, `confirm_delete_data(token)` validates and consumes it. Single-use, prevents replay.
3. Keep CSP tight (already done) to minimize XSS risk at the source

**Effort**: N/A for isolation mode. Path validation: Done. Confirmation tokens: Done.

#### B3. Capability Restructuring

The monolithic `default.json` with 45+ permissions should be split into logical groups:
```
capabilities/
  core.json        — core:default, app, event, path, resources
  window.json      — core:window:*, core:webview:default
  tray.json        — core:tray:*, core:menu:*
  shortcuts.json   — global-shortcut:*
  filesystem.json  — opener:allow-reveal-item-in-dir, dialog:allow-open
```

Per-window capabilities would be especially valuable when adding the global search bar window (grant it only search-related permissions, not `request_delete_data`).

#### B4. DevTools in Release Builds

`devtools` is enabled unconditionally in Cargo.toml features. DevTools access allows arbitrary script execution. Should be conditionally compiled:
```toml
[features]
devtools = ["tauri/devtools"]
```
Then only enable via `--features devtools` for debug builds.

#### B5. XSS Surface — Already Mitigated, Monitor for Drift

The frontend uses `dangerouslySetInnerHTML` in `ResultRow.tsx`, `ResultList.tsx`, and `ContextInspector.tsx` to render highlighted search results. However, all content flows through `resultMapper.ts` where `escapeHTML()` is applied before `<mark>` tags are injected (`highlightText`, `highlightSnippet`, `renderSpanHighlights` all escape first). An explicit XSS test exists in `resultMapper.test.ts` testing `<script>alert("xss")</script>` in excerpt regions.

**Current risk: Low.** The escaping is consistent across all rendering paths. The concern is drift — a future code change could introduce a path that skips `escapeHTML`. Maintain the existing test coverage and ensure any new `dangerouslySetInnerHTML` usage goes through the mapper pipeline.

### C. Windows OS Integration

#### C1. Taskbar Progress Bar — LOW effort, HIGH value

Tauri v2 natively provides `Window::set_progress_bar()` with Normal, Paused, Error, and Indeterminate states. Maps directly to `ITaskbarList3`. The shell would receive indexing progress events from the Java backend and update the taskbar.

**Effort**: Low. Use existing Tauri API, wire to backend status polling.

#### C2. Jump Lists — MEDIUM effort, MEDIUM value

No native Tauri API. Requires custom Rust plugin wrapping `ICustomDestinationList` COM API. Would show recent searches and pinned indexed folders in the taskbar right-click menu.

**Effort**: Medium. Custom COM code, but well-documented Win32 pattern.

#### C3. Explorer Context Menu ("Search with JustSearch") — HIGH effort, MEDIUM value

More practical than file associations for a search app. Requires a shell extension DLL registered during NSIS install. Right-click a file/folder → "Search with JustSearch" → opens the app with a search scoped to that location.

**Effort**: High. Requires separate C++/Rust DLL, registry registration, and NSIS hook changes.

### D. Process Management Improvements

#### D1. Process Watcher / Heartbeat

**Current state**: Backend death is detected only when stdout pipes close or the frontend's `/api/status` poll fails. No explicit process watcher.

**Improvement**: Spawn a tokio task that periodically checks if the child process is still alive (`child.try_wait()`). On unexpected death: log the exit code, optionally restart, and notify the frontend via a Tauri event.

#### D2. `tauri-plugin-shell` Evaluation

The official `tauri-plugin-shell` provides managed child process spawning with stdin/stdout/stderr piping, event-based output streaming, and lifecycle management. This directly overlaps with the manual `Command::new("javaw.exe")` pattern in lib.rs.

**Trade-offs**: The plugin provides a cleaner API and frontend-accessible process events, but the current manual approach gives full control over stdout parsing (port/token discovery) and log redirection. Evaluate whether the plugin can handle the custom stdout protocol.

#### D3. Backend Restart Capability

Currently, if the backend crashes, the user must restart the entire application. The shell could detect backend death (via D1) and offer restart without restarting the Tauri process. The port/token discovery logic would re-run, and the frontend would reconnect automatically (the reconnection logic already exists with exponential backoff).

#### D4. Structured Shell Logging

**Current state**: All Java stdout/stderr → `headless-backend.log` with 10 MB rotation. No structured logging in the Rust shell itself.

**Plugin**: `tauri-plugin-log` provides configurable logging with targets (file, stdout, webview console) and structured fields. Could unify Rust-side and JS-side logging into a single pipeline with timestamps and severity levels.

### E. IPC Architecture

#### E1. Channels for Streaming Data

Tauri v2 provides four IPC mechanisms with different performance profiles:

| Mechanism | Pattern | Best For |
|-----------|---------|----------|
| Commands (`invoke`) | Request-response | Config reads, one-shot queries |
| Events | Fire-and-forget, bidirectional | Lifecycle notifications, infrequent updates |
| **Channels** | Fast, ordered, Rust→JS streaming | **Search results, agent progress, index status** |
| Custom Protocol | URI scheme, HTTP-like | Binary data (thumbnails, cached pages) |

**Current state**: JustSearch uses Commands for Tauri IPC and HTTP for backend communication. Events and Channels are unused.

**Opportunity**: If the Tauri shell ever proxies backend data (rather than the frontend calling the Java API directly), Channels would be the right mechanism for streaming search results and agent responses. The current architecture (frontend → Java backend directly via HTTP) makes this less urgent, but it's the right pattern if the shell takes on more orchestration responsibility.

#### E2. `removeUnusedCommands` (Tauri 2.4+) — ENABLED

Setting `"removeUnusedCommands": true` in `tauri.conf.json` enables dead code elimination for IPC commands not referenced in ACL capability files. Free bundle size reduction. **Enabled 2026-02-18** (Tauri 2.10.2 >= 2.4 requirement).

### F. Plugin Opportunities

Plugins from the official Tauri v2 catalog (30 plugins total) that could benefit JustSearch:

| Plugin | Use Case | Priority |
|--------|----------|----------|
| `store` (v2.4.2) | Persistent KV storage — could simplify frontend config persistence | Low |
| `process` | `relaunch()` API for clean restart-after-update | Medium (pairs with updater) |
| `clipboard-manager` | Copy search result snippets to clipboard | Low |
| `positioner` | Position quick-search bar relative to tray/screen center | Medium (pairs with search bar) |
| `fs` | Sandboxed filesystem access with scoped permissions | Future (if moving away from direct HTTP) |
| `persisted-scope` | Save user-granted folder access across restarts | Low |
| `os` | Query OS type, version, arch, locale for diagnostics | Low |
| `cli` | Parse CLI arguments (e.g., `--portable`, `--data-dir`) if CLI launch flags are added | Low |

### G. Cross-Platform Readiness

> **Scoping note**: JustSearch is currently Windows-only with no stated plans for cross-platform. This section is included for reference. If cross-platform becomes a goal, extract to a dedicated tempdoc with deeper investigation.

Here's what cross-platform support would require:

#### G1. Platform Support Matrix

| Aspect | Windows | macOS | Linux |
|--------|---------|-------|-------|
| WebView | WebView2 (Chromium) | WKWebView (WebKit) | WebKitGTK (WebKit) |
| Installer | NSIS (current) | DMG, .app bundle | .deb, AppImage |
| Tray icon | Works | Works | Works on X11; Wayland issues (tauri #14234) |
| Global shortcuts | Works | Works | Works on X11; **may not work on Wayland** (security restrictions) |
| Single instance | Works | Works | Works for .deb/AppImage; broken in Flatpak/Snap (DBus blocked) |
| Code signing | Authenticode (configured) | Apple notarization required | N/A (optional GPG) |

#### G2. Windows-Specific Code That Needs Porting

| Windows Code | macOS/Linux Equivalent |
|-------------|----------------------|
| `CREATE_NO_WINDOW` (0x08000000) | Not needed (no console window on Unix) |
| `taskkill /PID <pid> /T` | `kill -TERM <pgid>` (process group kill) |
| `taskkill /PID <pid> /T /F` | `kill -9 <pgid>` |
| `explorer /select,<path>` | `open -R <path>` (macOS) / `xdg-open <parent>` (Linux) |
| `javaw.exe` | `java` |
| Classpath separator `;` | Classpath separator `:` |

The code already has `#[cfg(windows)]` / `#[cfg(not(windows))]` blocks for most of these. The main gap: the Unix fallback (line 113-114 of lib.rs) only calls `child.kill()` + `child.wait()`, which kills the direct child but NOT its descendants. Needs process group kill:
```rust
#[cfg(unix)]
cmd.process_group(0);  // Create new process group at spawn
// Then kill with negative PID to kill the group
```

#### G3. llama-server Binary Per Platform

| Platform | Binary | GPU |
|----------|--------|-----|
| Windows x86_64 | `llama-server.exe` + DLLs | CUDA, Vulkan |
| macOS aarch64 | `llama-server` | Metal (built-in) |
| macOS x86_64 | `llama-server` | CPU only |
| Linux x86_64 | `llama-server` | CUDA, Vulkan, CPU |

CI must fetch the correct platform binary from llama.cpp releases and include it in the bundle resources.

#### G4. macOS Concerns

- **Notarization**: Required for distribution outside the App Store. Tauri's GitHub Action handles it with the right env vars (`APPLE_CERTIFICATE`, `APPLE_SIGNING_IDENTITY`, etc.)
- **Universal binaries**: `--target universal-apple-darwin` produces a single .app for both Intel and Apple Silicon. Doubles binary size and build time. The JRE and llama-server inside the bundle must also be universal or detected at runtime
- **App Sandbox**: Needs entitlements for user-selected directories, loopback network, GPU access

#### G5. Linux Concerns

- **Packaging**: Start with `.deb` + AppImage. Avoid Flatpak/Snap initially (DBus-related breakage with single-instance plugin)
- **Wayland**: Tray icons may not appear in dev mode; global shortcuts may not work (Wayland security model prevents global key capture). This could break the search hotkey feature
- **Build deps**: `libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf`

#### G6. Multi-Platform CI/CD

Tauri provides `tauri-apps/tauri-action` for GitHub Actions matrix builds. JustSearch-specific challenges: JRE bundling (need JDK on CI), llama-server binary fetching per platform, macOS universal binary building.

### H. Accessibility

#### H1. Screen Reader Support — CRITICAL BUG

**Confirmed bug** (tauri #12901): NVDA screen reader does not read text in frameless Tauri windows with `decorations: false`. Introduced between Tauri v2.2.5 and v2.3.1. Marked priority:high but unresolved.

**Impact**: JustSearch uses `decorations: false`. If this bug affects the current Tauri version, screen reader users cannot use the application at all.

**Mitigation options**:
1. Monitor and test against specific Tauri versions
2. Offer a "standard title bar" user preference that sets `decorations: true`
3. Add comprehensive ARIA landmarks to the custom title bar (`role="banner"`, `role="toolbar"` for window controls, `aria-label` on all custom buttons)

#### H2. Windows High Contrast Mode

When users enable High Contrast Mode, `forced-colors: active` CSS media query activates. Custom backgrounds, text colors, and borders are overridden. A custom-styled frameless app can become unusable.

**Fix**: Add `@media (forced-colors: active)` CSS rules that reset custom styling to system colors (`Canvas`, `CanvasText`, `ButtonText`, `Field`, `FieldText`).

#### H3. Keyboard Navigation

Custom title bar buttons (minimize/maximize/close) are HTML elements, not native controls. Need:
- `role="button"` with `aria-label` on each
- `tabindex="0"` for keyboard focusability
- Enter/Space key handlers
- Clear tab order boundary between title bar and main content

#### H4. Reduced Motion

Add `@media (prefers-reduced-motion: reduce)` to disable or reduce animations in the UI.

### I. Multi-Monitor DPI Handling

**Known upstream bugs**:
- Window size increases when dragged between monitors with different DPI (tauri #3610)
- Awkward dragging behavior on cross-monitor moves (tauri #12043)
- WebView2 uses wrong DPI scale during mid-drag transition (WebView2Feedback #58)

**`tauri-plugin-window-state` limitations**:
- Does not remember which monitor the window was on (plugins-workspace #1282)
- If saved position references a disconnected monitor, window opens off-screen
- If closed while minimized, invalid position values are persisted

**Long-term fix**: Custom window state persistence that:
1. Saves monitor identifier alongside position/size
2. On restore, enumerates `Window::available_monitors()` and validates saved position is within bounds
3. Falls back to centering on primary monitor if saved monitor is missing
4. Listens for `ScaleFactorChanged` events for runtime DPI changes

### J. CLI Companion Tool

**Concept**: Ship a lightweight `justsearch-cli` binary alongside the main app. It reads the backend port from a well-known file and makes HTTP calls to the running backend.

```
justsearch search "quarterly report"    # Search from terminal
justsearch ingest ~/Documents/new/      # Trigger indexing
justsearch status                       # Show backend health
```

**Why standalone > Tauri CLI plugin**: The `tauri-plugin-cli` approach requires launching the full Tauri app (including WebView2) to process CLI commands — slow and heavy. A standalone binary can query the Java backend directly over HTTP with instant response.

**Implementation**: Small Rust binary (~500 LOC). Reads port from `%APPDATA%\JustSearch\api-port` (or equivalent). Uses `reqwest` for HTTP calls. Ship as a Tauri sidecar or install alongside the main binary.

### K. Browser Extension Integration

**Concept**: A browser extension that communicates with JustSearch's local API for "Search in JustSearch" context menu on selected text, or sending the current page for indexing.

**Approach**: Localhost HTTP API (simplest). The browser extension makes `fetch()` calls to `http://127.0.0.1:<port>/api/...`. Requires the backend to set CORS headers for the extension origin.

**Alternative**: Chrome Native Messaging (more secure, no CORS needed, but requires a bridge executable registered via registry).

**Effort**: High. Requires building/maintaining a browser extension, handling CORS or native messaging, and version compatibility.

### L. MCP Search Exposure

**Concept**: Expose JustSearch's search index as an MCP (Model Context Protocol) server, allowing AI assistants and agents to search the user's local knowledge base. The backend already has an MCP server implementation (`rmcp` crate in Cargo.toml, used for test harness). Wox v2 and screenpipe already ship MCP servers for similar purposes.

**Why it matters**: As AI agents become mainstream desktop tools, local search becomes a key data source. An MCP-exposed search tool lets Claude, Cursor, or other MCP clients query the user's indexed documents without manual copy-paste.

**Current state**: The `rmcp` dependency exists for the smoke test harness. Extending it to expose `search_index` as a tool with proper schema would be relatively low effort. The transport could be stdio (launched as sidecar) or HTTP (query the existing REST API).

**Effort**: Low-Medium. The hard infrastructure (search API, MCP crate) already exists. Needs: tool schema definition, auth/access control decisions, and a way for MCP clients to discover the server.

### M. Bundle Size & Performance

#### L1. Rust Release Profile

Current: `opt-level = "s"`, `strip = true`. Additional optimizations:
```toml
[profile.release]
panic = "abort"       # Remove panic unwinding code
codegen-units = 1     # Better LLVM optimization (slower build)
lto = true            # Link-time optimization
```

#### L2. Dead Code Elimination

`removeUnusedCommands` (Tauri 2.4+) removes IPC commands not referenced in ACL files. Free size reduction. Available now (current Tauri is 2.10.2).

#### L3. WebView2 Installer Trade-offs

| Mode | Size | Notes |
|------|------|-------|
| Download bootstrapper | +0 MB | Requires internet at install |
| Offline installer (current) | +127 MB | No internet needed |
| Fixed version | +180 MB | Enterprise version pinning |

The current `offlineInstaller` choice is correct for a local-first app that shouldn't require internet.

---

## Implementation Sketch: System Tray (Applied 2026-02-17)

The following code was implemented in lib.rs. Retained here as reference for the design decisions:

**lib.rs changes** (in `setup()` closure, after `BackendState` creation):

```rust
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::menu::{Menu, MenuItem};

// Build tray menu
let show_i = MenuItem::with_id(app, "show", "Show JustSearch", true, None::<&str>)?;
let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
let menu = Menu::with_items(app, &[&show_i, &quit_i])?;

TrayIconBuilder::new()
    .icon(app.default_window_icon().unwrap().clone())
    .tooltip("JustSearch")
    .menu(&menu)
    .on_menu_event(|app, event| {
        match event.id.as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
            "quit" => app.exit(0),
            _ => {}
        }
    })
    .on_tray_icon_event(|tray, event| {
        if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
            if let Some(w) = tray.app_handle().get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            }
        }
    })
    .build(app)?;
```

**Window close behavior change** (in `on_window_event`):

```rust
// Instead of killing backend on close, hide to tray
if let WindowEvent::CloseRequested { api, .. } = event {
    api.prevent_close();  // Don't actually close
    let _ = window.hide(); // Hide to tray instead
    // Backend keeps running — no kill_child() here anymore
}
```

**Backend cleanup on real quit**: `kill_child()` must move out of the `CloseRequested` handler (which now hides instead of closing) to the `RunEvent::Exit` path. `app.exit(0)` from the tray menu fires `RunEvent::Exit`, not `CloseRequested`. This requires a second `BackendState` clone:

```rust
// Need a second clone for the exit handler
let state_for_exit = backend_state.clone();

// ... after .build(tauri::generate_context!()):
.run(|_app, event| {
    if let tauri::RunEvent::Exit = event {
        state_for_exit.kill_child();
    }
});
```

---

## Full Priority Matrix

| # | Feature | Impact | Effort | Tier |
|---|---------|--------|--------|------|
| 1 | ~~Window state persistence~~ | High | Low (caveats) | ~~**T1**~~ **DONE** (2026-02-17) |
| 2 | ~~System tray (close-to-tray)~~ | High | Low | ~~**T1**~~ **DONE** (2026-02-17) |
| 3 | ~~Enable CSP~~ | High (security) | Low | ~~**T1**~~ **DONE** (2026-02-17) |
| 4 | Auto-updater | High | Medium | **T2** — needs infrastructure (keys, hosting, CI) |
| 5 | Splash screen | Medium | Medium | **T2** — eliminates white-flash on cold start |
| 6 | Taskbar progress bar | High | Low | **T2** — native Tauri API, wire to backend |
| 7 | ~~Auto-launch on startup~~ | Medium | Low | ~~**T3**~~ **DONE** (2026-02-17) |
| 8 | ~~Notifications~~ | Medium | Low | ~~**T3**~~ **DONE** (2026-02-17) |
| 9 | Global search bar | Very High | Medium-High | **Long-term A** |
| 10 | ~~Isolation mode~~ ~~IPC path validation~~ | High (security) | Low | ~~**Long-term B**~~ **DONE** (2026-02-18) — isolation mode not viable; `validate_user_path()` added |
| 11 | Capability restructuring | Medium (security) | Low | **Long-term B** |
| 12 | DevTools conditional compile | Medium (security) | Low | **Long-term B** |
| 13 | Jump Lists | Medium | Medium | **Long-term C** |
| 14 | Process watcher / restart | Medium | Medium | **Long-term D** |
| 15 | Accessibility (ARIA, high-contrast) | High (compliance) | Medium | **Long-term H** |
| 16 | CLI companion | Medium | Medium | **Long-term J** |
| 17 | Cross-platform (macOS/Linux) | High (reach) | High | **Long-term G** |
| 18 | Browser extension | Medium | High | **Long-term K** |
| 19 | Explorer context menu | Medium | High | **Long-term C** |
| 20 | Custom multi-monitor state | Medium | Medium | **Long-term I** |
| 21 | MCP search exposure | Medium | Low-Medium | **Long-term L** — infrastructure already exists (rmcp crate + REST API) |

---

## Version Upgrade Investigation (2026-02-17)

### Current vs Latest

**Important discovery**: Although Cargo.toml originally pinned `tauri = "2.0.0"`, Cargo.lock had already resolved to 2.9.3 (semver `"2.0.0"` means `>=2.0.0, <3.0.0`). The NVDA bug and all 2.3+ features were already in play before this upgrade was performed.

**Post-upgrade state (2026-02-18)**:

| Crate | Version |
|-------|---------|
| `tauri` | 2.10.2 |
| `tauri-build` | 2.5.5 |
| `tauri-plugin-window-state` | 2.4.1 |
| `tauri-plugin-autostart` | 2.5.1 |
| `tauri-plugin-notification` | 2.3.3 |
| `tauri-plugin-dialog` | 2.6.0 |
| `tauri-plugin-opener` | 2.5.3 |
| `tauri-plugin-single-instance` | 2.4.0 |

All Cargo.toml pins normalized to `"2"`. `global-shortcut` removed (was unused). All 8 cargo tests pass, zero TypeScript errors in source files, clean compilation.

### Breaking Changes Assessment

Tauri follows semver — no hard API breaks in 2.x. However, conditionally breaking changes exist:

1. **v2.3.0**: Undecorated windows (`decorations: false`) get **native resize handles outside the client area**. This could affect JustSearch's frameless window layout. Needs visual testing.
2. **v2.3.0**: `Manager::unmanage` deprecated (not used by JustSearch).
3. **v2.6.0**: Callback registration moved from `window['{id}']` to `window.__TAURI_INTERNALS__.callbacks`. Only affects custom JS accessing raw callbacks — standard `@tauri-apps/api` usage is unaffected.
4. **v2.3.0, v2.8.0, v2.10.0**: Upgraded `wry`/`windows`/`webview2-com` crates. Breaking for `with_webview` users. JustSearch does not use `with_webview` — no impact.

### NVDA Accessibility Bug — KEY RISK

**Issue**: tauri #12901 — NVDA screen reader does not read text in frameless windows (`decorations: false`). Introduced between Tauri 2.2.5 and 2.3.1. Labeled `priority: 1 high` but **still open and unfixed** as of 2026-02.

**Impact on JustSearch**: JustSearch uses `decorations: false` and is now on Tauri 2.10.2 (past the 2.3.0 regression). This bug is present in current builds. Screen reader users cannot use the application until mitigated.

**Mitigation** (not yet implemented):
- Add a "standard title bar" accessibility preference (`decorations: true`) that users can enable. This is the practical path since the upstream bug is unresolved.

### New Features Gained by Upgrading

| Version | Feature | Relevance |
|---------|---------|-----------|
| 2.3+ | `emit_str` / `emit_str_to` | Bypasses serde double-serialization. Useful for Java backend JSON passthrough. |
| 2.4+ | `removeUnusedCommands` | Dead code elimination for IPC commands. Free binary size reduction. |
| 2.4+ | `Webview::reload()` | Enables hot-reload without full app restart. |
| 2.5+ | `initialization_script_on_all_frames` | Not needed currently. |
| 2.8+ | `focusable` attribute | Could help with global search bar focus management. |
| 2.8+ | Cookie management | Not needed (loopback API uses session tokens). |

### Upgrade Procedure

1. Normalize all version pins in Cargo.toml to `"2"` (Cargo semver resolves to latest 2.x)
2. Run `cargo update` in `modules/shell/src-tauri/`
3. Update npm packages: `@tauri-apps/api@latest` plus all plugin npm packages
4. Build and test — watch for frameless window layout changes (resize handles in 2.3+)
5. Optionally enable `"build": { "removeUnusedCommands": true }` in tauri.conf.json
6. Test NVDA behavior if accessibility is a concern

### Result

**Upgrade completed 2026-02-17.** Tauri 2.9.3 → 2.10.2, all plugins updated, Cargo.toml pins normalized.

**Remaining follow-ups**:
1. ~~Enable `removeUnusedCommands` in tauri.conf.json~~ **Done 2026-02-18**
2. The NVDA bug (#12901) is present — add "standard title bar" accessibility option to the backlog
3. Test frameless window layout for any visual changes from the 2.3.0 native resize handle addition
4. Consider using `emit_str` for Java backend JSON passthrough if performance becomes a concern
5. Test CSP against a production build (`tauri build`) to confirm no breakage

---

## Staleness review (2026-05-18)

Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention. Classification: ABANDONED. Stale for 89 days at audit time.

