import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const WALK_EXCLUDES = new Set(['build', 'node_modules', 'dist', '.git', '.gradle', 'tmp']);

/**
 * Find durable `*Store.java` implementations under module main-source trees.
 *
 * A store is considered durable when one of its constructors accepts a Path.
 * The result is a sorted list of repository-relative POSIX paths.
 */
export function scanDurableStores(root) {
  const out = [];
  for (const abs of walk(resolve(root, 'modules'), (name) => name.endsWith('Store.java'))) {
    const rel = norm(relative(root, abs));
    if (!rel.includes('/src/main/java/')) continue;

    const simpleName = rel.split('/').at(-1).replace(/\.java$/, '');
    const source = readFileSync(abs, 'utf8');
    const constructor = new RegExp(
      String.raw`(?<!new\s)\b${escapeRegExp(simpleName)}\s*\(([^;{)]*)\)`,
      'g',
    );
    let match;
    while ((match = constructor.exec(source)) !== null) {
      if (/\bPath\b/.test(match[1])) {
        out.push(rel);
        break;
      }
    }
  }
  return out.sort();
}

function walk(dir, keep) {
  const out = [];
  if (!existsSync(dir)) return out;

  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }

  for (const entry of entries) {
    if (WALK_EXCLUDES.has(entry.name)) continue;
    const absolute = join(dir, entry.name);
    let isDirectory = entry.isDirectory();
    if (entry.isSymbolicLink()) {
      try {
        isDirectory = statSync(absolute).isDirectory();
      } catch {
        continue;
      }
    }
    if (isDirectory) out.push(...walk(absolute, keep));
    else if (keep(entry.name)) out.push(absolute);
  }
  return out;
}

function norm(path) {
  return String(path ?? '').replace(/\\/g, '/');
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
