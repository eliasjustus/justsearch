import fs from 'node:fs';
import path from 'node:path';

const TELEMETRY_DIR = 'tmp/agent-telemetry';
const EVENTS_FILE = 'events.ndjson';
const ERRORS_FILE = 'errors.log';

// Rotate when events.ndjson exceeds 10 MB. Previous file is kept as .prev
// (one generation only — simple and bounded). Increased from 5 MB to reduce
// data loss from rotation (score instability root cause, see tempdoc 118).
const MAX_EVENTS_BYTES = 10 * 1024 * 1024;

function ensureDir(dirPath) {
  try {
    fs.mkdirSync(dirPath, { recursive: true });
  } catch {
    // If mkdir fails, appendFileSync will also fail — caught by caller.
  }
}

function rotateIfNeeded(filePath) {
  try {
    const stat = fs.statSync(filePath);
    if (stat.size > MAX_EVENTS_BYTES) {
      fs.renameSync(filePath, filePath + '.prev');
    }
  } catch {
    // File doesn't exist yet or stat failed — nothing to rotate.
  }
}

/**
 * Append a single NDJSON line to `tmp/agent-telemetry/events.ndjson`.
 *
 * Synchronous: each hook process writes one line and exits.
 * On NTFS/ext4, appendFileSync with small buffers (<4KB) is effectively atomic.
 */
export function appendEventSync(repoRoot, event) {
  const dir = path.join(repoRoot, TELEMETRY_DIR);
  ensureDir(dir);
  const filePath = path.join(dir, EVENTS_FILE);
  rotateIfNeeded(filePath);
  const line = JSON.stringify(event) + '\n';
  fs.appendFileSync(filePath, line, 'utf8');
}

/**
 * Log an error to `tmp/agent-telemetry/errors.log`.
 * Best-effort — errors here are silently swallowed.
 */
export function logErrorSync(repoRoot, message) {
  try {
    const dir = path.join(repoRoot, TELEMETRY_DIR);
    ensureDir(dir);
    const line = `${new Date().toISOString()} ${message}\n`;
    fs.appendFileSync(path.join(dir, ERRORS_FILE), line, 'utf8');
  } catch {
    // Nothing — error logging must not throw.
  }
}
