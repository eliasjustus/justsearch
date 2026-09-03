#!/usr/bin/env node
/**
 * Tempdoc 696 — single JDK resolver for dev tooling.
 *
 * The Gradle wrapper and every dev JVM launcher (`ui.bat`, worker, hot-swap,
 * installDist) use whatever `JAVA_HOME` points at, with NO version check. When a
 * stale JDK 8 lands on `JAVA_HOME` (e.g. a scoop temurin8 install rewrote the
 * user env — see observations), `dev_start`/hot-swap/prepare-worktree fail with
 * an opaque "Gradle assemble failed" or a 15s port timeout, because Gradle 9.6.1
 * needs 17+ and the build's `-XX:+UseCompactObjectHeaders` /
 * `--sun-misc-unsafe-memory-access=warn` need 24+.
 *
 * This module resolves a JDK whose `java` is >= 24 (canonical target: Temurin 25)
 * and is consumed by dev-runner.cjs, justsearch-dev-mcp/server.mjs (hot-swap) and
 * prepare-worktree.cjs, which inject the result as `JAVA_HOME` into every JVM
 * spawn's env. The healthy case (a >= 24 JDK already on `JAVA_HOME`) is a no-op.
 *
 * Pure helpers (`parseJavaMajor`, `selectFromCandidates`) are exported under
 * `__test` so the version/selection logic unit-tests without a real JDK.
 */
'use strict';

const os = require('os');
const path = require('path');
const fs = require('fs');
const { spawnSync } = require('child_process');

// Minimum bootstrap JDK: the build's JVM args (UseCompactObjectHeaders — JDK 24;
// --sun-misc-unsafe-memory-access=warn — JDK 23) require >= 24. Canonical target
// is 25 (CI `JAVA_VERSION`, the global toolchain); >= 24 is the accept floor.
const MIN_MAJOR = 24;

/**
 * Parse the major version from `java -version` output. Handles the legacy
 * `1.8.0_492` (→ 8) and modern `25.0.2` / `21` (→ 25 / 21) formats.
 * @returns {number|null}
 */
function parseJavaMajor(versionOutput) {
  if (!versionOutput) return null;
  const m = String(versionOutput).match(/version "(\d+)(?:\.(\d+))?[.\d_]*"/);
  if (!m) return null;
  const first = parseInt(m[1], 10);
  if (first === 1 && m[2] != null) return parseInt(m[2], 10); // 1.8 → 8
  return Number.isNaN(first) ? null : first; // 25 → 25
}

function javaExeIn(home) {
  return path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
}

/** Probe a java executable's major version, or null if it can't run. */
function probeJavaMajor(javaExe) {
  try {
    const r = spawnSync(javaExe, ['-version'], { encoding: 'utf8', windowsHide: true });
    if (r.error) return null;
    // `java -version` writes to stderr; concatenate defensively.
    return parseJavaMajor((r.stderr || '') + (r.stdout || ''));
  } catch {
    return null;
  }
}

/**
 * Given candidate JDK homes in priority order, return the first whose `java` is
 * >= MIN_MAJOR. `probe` is injectable so this is unit-testable without a real JDK.
 * @returns {string|null}
 */
function selectFromCandidates(candidates, probe = probeJavaMajor) {
  for (const home of candidates) {
    if (!home) continue;
    const exe = javaExeIn(home);
    if (!fs.existsSync(exe)) continue;
    const major = probe(exe);
    if (major != null && major >= MIN_MAJOR) return home;
  }
  return null;
}

/**
 * Derive candidate JDK homes from java executables visible on PATH.
 *
 * Resolving the executable first handles Unix alternatives and Windows junctions.
 * Non-JDK shims are harmless: selectFromCandidates subsequently requires
 * <candidate>/bin/java and probes its version.
 */
function javaHomesFromPath(
  pathValue = process.env.PATH,
  platform = process.platform,
  exists = fs.existsSync,
  realpath = fs.realpathSync,
) {
  if (!pathValue) return [];

  const pathApi = platform === 'win32' ? path.win32 : path.posix;
  const delimiter = platform === 'win32' ? ';' : ':';
  const executableName = platform === 'win32' ? 'java.exe' : 'java';
  const homes = [];
  const seen = new Set();

  for (const rawEntry of pathValue.split(delimiter)) {
    const entry = rawEntry.trim().replace(/^"(.*)"$/, '$1');
    if (!entry) continue;

    const executable = pathApi.join(entry, executableName);
    if (!exists(executable)) continue;

    let resolvedExecutable = executable;
    try {
      resolvedExecutable = realpath(executable);
    } catch {
      // A runnable path entry can still be useful when canonicalization is unavailable.
    }
    const home = pathApi.dirname(pathApi.dirname(resolvedExecutable));
    const key = platform === 'win32' ? home.toLowerCase() : home;
    if (!seen.has(key)) {
      seen.add(key);
      homes.push(home);
    }
  }

  return homes;
}

/** Enumerate candidate JDK homes in priority order (existence checked later). */
function candidateHomes() {
  const c = [];
  // 1. Ambient JAVA_HOME — the healthy dev's setup; keeping it first makes the
  //    resolver a no-op when JAVA_HOME is already a >= 24 JDK.
  if (process.env.JAVA_HOME) c.push(process.env.JAVA_HOME);
  // 2. Explicit override — the portable escape hatch (documented in the throw).
  if (process.env.JUSTSEARCH_DEV_JDK_HOME) c.push(process.env.JUSTSEARCH_DEV_JDK_HOME);
  // 3. PATH — GUI hosts can inherit PATH while omitting JAVA_HOME/SCOOP. Inspect
  //    every visible java, because an obsolete system shim may precede a valid JDK.
  c.push(...javaHomesFromPath());
  // 4. Gradle's foojay auto-download cache — the least machine-specific root; if a
  //    prior build provisioned the 25 toolchain, it lives here.
  const gradleJdks = path.join(os.homedir(), '.gradle', 'jdks');
  try {
    for (const d of fs.readdirSync(gradleJdks)) c.push(path.join(gradleJdks, d));
  } catch { /* no cache */ }
  // 5. scoop (this repo's common Windows setup) — SCOOP may be on any drive.
  const scoopRoots = [process.env.SCOOP, path.join(os.homedir(), 'scoop')].filter(Boolean);
  for (const root of scoopRoots) c.push(path.join(root, 'apps', 'temurin25-jdk', 'current'));
  // 6. Standard OS install locations (best-effort; the env override covers the rest).
  if (process.platform === 'win32') {
    const adoptium = 'C:\\Program Files\\Eclipse Adoptium';
    try {
      for (const d of fs.readdirSync(adoptium)) {
        if (/jdk-2[4-9]/.test(d)) c.push(path.join(adoptium, d));
      }
    } catch { /* not installed */ }
  } else if (process.platform === 'darwin') {
    c.push('/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home');
  } else {
    for (const p of ['/usr/lib/jvm/temurin-25-jdk', '/usr/lib/jvm/temurin-25-jdk-amd64']) c.push(p);
  }
  return c;
}

let _cached = null;

/**
 * Resolve a JDK-home whose `java` is >= 24 (target Temurin 25). Throws with an
 * actionable message if none is found (fail-fast, per observations — better than
 * a 15s port timeout or an opaque assemble failure). Memoized per process.
 * @returns {string}
 */
function resolveJdkHome() {
  if (_cached) return _cached;
  const home = selectFromCandidates(candidateHomes());
  if (!home) {
    const ambient = process.env.JAVA_HOME || '(unset; PATH java)';
    const ambientMajor = probeJavaMajor(
      process.env.JAVA_HOME ? javaExeIn(process.env.JAVA_HOME) : 'java',
    );
    throw new Error(
      `[resolve-jdk] No JDK >= ${MIN_MAJOR} found — JustSearch dev tooling needs Temurin 25. ` +
        `JAVA_HOME=${ambient} is JDK ${ambientMajor ?? 'unknown'}. ` +
        `Fix: point JAVA_HOME (or set JUSTSEARCH_DEV_JDK_HOME) at a JDK 25 install. See tempdoc 696.`,
    );
  }
  _cached = home;
  return home;
}

/** The `java` executable of the resolved JDK. */
function resolveJavaExe() {
  return javaExeIn(resolveJdkHome());
}

module.exports = {
  resolveJdkHome,
  resolveJavaExe,
  javaExeIn,
  __test: { parseJavaMajor, selectFromCandidates, javaHomesFromPath, candidateHomes, MIN_MAJOR },
};
