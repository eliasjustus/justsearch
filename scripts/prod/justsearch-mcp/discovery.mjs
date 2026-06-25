/**
 * Port discovery, data directory resolution, and session token acquisition
 * for the JustSearch production MCP server.
 *
 * Data dir resolution replicates PlatformPaths.java:66-120.
 * Port file location follows HeadlessApp.java:223-247.
 */

import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import {findRunningManifest as findManifestShared, isPidAlive} from '../../lib/platform-paths.mjs';

/**
 * Resolve the JustSearch data directory.
 * Mirrors PlatformPaths.resolveDataDir() from
 * modules/configuration/src/main/java/io/justsearch/configuration/PlatformPaths.java
 *
 * Priority: JUSTSEARCH_DATA_DIR env var -> platform default.
 * JVM-only legacy sysprops (justsearch.data_dir, app.data_dir) are skipped.
 */
export function resolveDataDir() {
  const env = process.env.JUSTSEARCH_DATA_DIR;
  if (env && env.trim()) return path.resolve(env.trim());

  if (process.platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA;
    if (localAppData && localAppData.trim()) {
      return path.join(localAppData, 'JustSearch');
    }
    return path.join(os.homedir(), 'AppData', 'Local', 'JustSearch');
  }

  if (process.platform === 'darwin') {
    return path.join(os.homedir(), 'Library', 'Application Support', 'JustSearch');
  }

  return path.join(os.homedir(), '.justsearch');
}

// Tempdoc 501 Phase 18: the legacy `readPortFile` helper (read
// <dir>/runtime/api-port.txt) is removed alongside the producer's api-port.txt
// write. Discovery now flows exclusively through findRunningManifest() →
// manifest.json, exposed at the top of this module via the platform-paths
// shared library.

/**
 * Validate that a JustSearch instance is responding on the given port.
 * Issues GET /api/status and confirms the response contains status: "ok".
 */
export function validatePort(port, timeoutMs = 2000) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port,
        path: '/api/status',
        method: 'GET',
        timeout: timeoutMs,
        headers: { Accept: 'application/json' },
      },
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          return resolve(false);
        }
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('error', () => resolve(false));
        res.on('end', () => {
          try {
            const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            resolve(json.status === 'ok');
          } catch {
            resolve(false);
          }
        });
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', () => resolve(false));
    req.end();
  });
}

/**
 * Fetch the session token from GET /api/mcp/token.
 * Returns the token string, empty string (dev mode), or null (endpoint not available).
 */
export function fetchToken(port, timeoutMs = 3000) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port,
        path: '/api/mcp/token',
        method: 'GET',
        timeout: timeoutMs,
        headers: { Accept: 'application/json' },
      },
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          return resolve(null);
        }
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('error', () => resolve(null));
        res.on('end', () => {
          try {
            const json = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            resolve(typeof json.token === 'string' ? json.token : null);
          } catch {
            resolve(null);
          }
        });
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', () => resolve(null));
    req.end();
  });
}

/**
 * Probe a range of ports for a running JustSearch instance.
 * Returns the first validated port or null.
 */
async function probePortRange(startPort, count, timeoutMs = 800) {
  for (let port = startPort; port < startPort + count; port++) {
    if (await validatePort(port, timeoutMs)) return port;
  }
  return null;
}

/**
 * Discover a running JustSearch instance.
 *
 * Priority:
 * 1. Explicit port (--port CLI arg)
 * 2. JUSTSEARCH_API_PORT env var
 * 3. api-port.txt from data dir
 * 4. api-port.txt from JUSTSEARCH_HOME
 * 5. Port probe 33221-33250
 *
 * Every candidate is validated with GET /api/status before acceptance.
 * Once validated, fetches the session token via GET /api/mcp/token.
 *
 * @param {{ port?: number, verbose?: boolean }} opts
 * @returns {Promise<{ port: number, baseUrl: string, token: string | null }>}
 */
export async function discover({ port: explicitPort, verbose } = {}) {
  const log = verbose ? (msg) => process.stderr.write(`[justsearch-mcp] ${msg}\n`) : () => {};
  const attempts = [];

  // 1. Explicit port (operator override; bypasses manifest discovery entirely).
  if (explicitPort != null) {
    log(`Trying explicit port ${explicitPort}...`);
    if (await validatePort(explicitPort)) {
      const token = await fetchToken(explicitPort);
      log(`Connected on port ${explicitPort}, token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
      return { port: explicitPort, baseUrl: `http://127.0.0.1:${explicitPort}`, token };
    }
    attempts.push(`--port ${explicitPort} (not responding)`);
  }

  // 2. JUSTSEARCH_API_PORT env var (legacy operator override).
  const envPort = parseInt(process.env.JUSTSEARCH_API_PORT, 10);
  if (Number.isFinite(envPort) && envPort > 0) {
    log(`Trying JUSTSEARCH_API_PORT=${envPort}...`);
    if (await validatePort(envPort)) {
      const token = await fetchToken(envPort);
      log(`Connected on port ${envPort}, token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
      return { port: envPort, baseUrl: `http://127.0.0.1:${envPort}`, token };
    }
    attempts.push(`JUSTSEARCH_API_PORT=${envPort} (not responding)`);
  }

  // 3. Runtime manifest (tempdoc 501 — the canonical discovery path).
  // findRunningManifest walks: env JUSTSEARCH_DATA_DIR → worktree/repo .dev-data
  // → platform default; reads each one's manifest; returns the first whose pid
  // is alive on this OS. Production installs land in the platform-default branch.
  const jsHome = process.env.JUSTSEARCH_HOME;
  const extras = jsHome && jsHome.trim() ? [jsHome.trim()] : [];
  const found = findManifestShared({extraDataDirs: extras});
  if (found) {
    const port = found.manifest?.head?.apiPort;
    if (port != null) {
      log(`Found manifest in ${found.dataDir} (instanceId=${found.manifest.instanceId})`);
      if (await validatePort(port)) {
        const token = await fetchToken(port);
        log(`Connected on port ${port}, token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
        return { port, baseUrl: `http://127.0.0.1:${port}`, token, instanceId: found.manifest.instanceId };
      }
      attempts.push(`manifest at ${found.dataDir} port ${port} (PID alive but HTTP probe failed)`);
    }
  } else {
    attempts.push('runtime manifest (no live producer found in any candidate dataDir)');
  }

  // 4. Port probe (last resort for ad-hoc launches with no metadata).
  // Tempdoc 501 Phase 18 removed the api-port.txt fallback: the canonical filesystem
  // transport is now manifest.json (checked above). No older-build path remains
  // worth carrying.
  log('Probing ports 33221-33250...');
  const probedPort = await probePortRange(33221, 30);
  if (probedPort != null) {
    const token = await fetchToken(probedPort);
    log(`Connected on port ${probedPort} (probed), token=${token ? 'obtained' : token === '' ? 'empty (dev mode)' : 'unavailable'}`);
    return { port: probedPort, baseUrl: `http://127.0.0.1:${probedPort}`, token };
  }
  attempts.push('port probe 33221-33250 (none responding)');

  throw new Error(
    'Could not find a running JustSearch instance.\n' +
      'Attempts:\n' +
      attempts.map((a) => `  - ${a}`).join('\n') +
      '\n\nMake sure JustSearch is running, or specify the port:\n' +
      '  node justsearch-mcp.mjs --port <port>\n' +
      '  JUSTSEARCH_API_PORT=<port> node justsearch-mcp.mjs',
  );
}

// Keep isPidAlive importable from this module too for any caller that wants
// freshness checks against an already-known port without re-running discovery.
export {isPidAlive};
