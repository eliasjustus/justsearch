/**
 * Dead-config scanner — tempdoc 799 §O.3 / §N.2.f.1.
 *
 * Answers the question the count ratchet cannot: **does anything actually READ this setting?**
 *
 * Why this exists. Tempdoc 754 found 28 knobs that resolved, were documented, and were read by
 * nothing because a live consumer hardcoded the value — "a false promise to users". 799 removed 22
 * of them and added the count ratchet, and then demonstrated the gap in the most direct way
 * available: four keys survived that cleanup with their `EnvRegistry` declarations and YAML
 * contributions intact, the full unit suite was green, `build -x test` was green, and the count
 * ratchet was green — because a key that resolves but is never read is invisible to all of them.
 * Only inspecting the running backend's own config surface exposed it (799 §N.2.f.1).
 *
 * A setting is considered READ if ANY of three paths reaches it. All three are real and in use, and
 * a scanner that knows only the first would fire ~30 false positives on this repo — which is how a
 * gate gets switched off (799 §O.1's failure mode) rather than obeyed:
 *
 *   1. it is resolved into a {@code ResolvedConfig} record component that some production file
 *      calls the accessor for;
 *   2. it is read directly through its {@code EnvRegistry} enum constant
 *      ({@code EnvRegistry.SOME_KEY.getString(...)});
 *   3. its raw key string appears anywhere in production sources outside the configuration module.
 *
 * Deliberately NOT a whole-program analysis: it is a string-level scan, so it can only prove a
 * NEGATIVE (nothing anywhere mentions this) — which is exactly the claim being made. Reflection or
 * a computed key name would be a false positive; none exist in this repo today, and the baseline
 * exists to absorb any that appear.
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const CFG_PKG = '/io/justsearch/configuration/';
const PRIMITIVES = new Set(['int', 'long', 'double', 'boolean', 'float', 'short', 'byte', 'char']);

function collectJavaMainSources(root) {
  const out = [];
  const modules = resolve(root, 'modules');
  if (!existsSync(modules)) return out;
  const walk = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = join(dir, e.name);
      if (e.isDirectory()) {
        if (!/^(build|node_modules|\.git|\.gradle|bin|out)$/.test(e.name)) walk(p);
      } else if (e.name.endsWith('.java') && p.replace(/\\/g, '/').includes('/src/main/')) {
        out.push(p);
      }
    }
  };
  walk(modules);
  return out;
}

/** Record components declared across every `public record` in ResolvedConfig. */
export function parseRecordComponents(resolvedConfigSource) {
  const comps = new Set();
  for (const m of resolvedConfigSource.matchAll(/public record (\w+)\(([\s\S]*?)\)\s*\{/g)) {
    for (const raw of m[2].split(',')) {
      const tokens = raw.trim().split(/\s+/);
      if (tokens.length < 2) continue;
      const name = tokens[tokens.length - 1];
      const type = tokens[tokens.length - 2];
      if (!/^[a-z][A-Za-z0-9]*$/.test(name)) continue;
      if (!(PRIMITIVES.has(type) || /^[A-Z]/.test(type) || type.endsWith('>'))) continue;
      comps.add(name);
    }
  }
  return comps;
}

/**
 * @returns {{deadKeys: string[], unreadComponents: string[], totals: {keys: number, components: number}}}
 */
export function scanDeadConfig(sourceRoot) {
  const cfg = resolve(sourceRoot, 'modules/configuration/src/main/java/io/justsearch/configuration');
  const rcPath = join(cfg, 'resolved', 'ResolvedConfig.java');
  const rcbPath = join(cfg, 'resolved', 'ResolvedConfigBuilder.java');
  const erPath = join(cfg, 'EnvRegistry.java');
  if (!existsSync(rcPath) || !existsSync(rcbPath) || !existsSync(erPath)) {
    return { deadKeys: [], unreadComponents: [], totals: { keys: 0, components: 0 }, skipped: true };
  }

  const rc = readFileSync(rcPath, 'utf8');
  const rcb = readFileSync(rcbPath, 'utf8');
  const er = readFileSync(erPath, 'utf8');

  const files = collectJavaMainSources(sourceRoot);
  const allSources = files.map((f) => readFileSync(f, 'utf8')).join('\n');
  const outsideConfig = files
    .filter((f) => !f.replace(/\\/g, '/').includes(CFG_PKG))
    .map((f) => readFileSync(f, 'utf8'))
    .join('\n');

  const components = parseRecordComponents(rc);
  const unreadComponents = [...components]
    .filter((c) => !new RegExp('\\.' + c + '\\s*\\(').test(allSources))
    .sort();

  const keyToConst = new Map();
  for (const m of er.matchAll(/([A-Z][A-Z0-9_]*)\(\s*"([a-z][\w.]+)"\s*,\s*"[A-Z_][A-Z0-9_]*"/g)) {
    keyToConst.set(m[2], m[1]);
  }
  const declared = new Set(keyToConst.keys());
  for (const m of rcb.matchAll(/putYaml\w*\(\s*"([^"]+)"/g)) declared.add(m[1]);
  const resolvedKeys = new Set();
  for (const m of rcb.matchAll(/resolve\w*\(\s*"([^"]+)"/g)) resolvedKeys.add(m[1]);

  const deadKeys = [];
  for (const key of [...declared].sort()) {
    if (resolvedKeys.has(key)) continue; // path 1
    const constant = keyToConst.get(key);
    if (constant && new RegExp('EnvRegistry\\.' + constant + '\\b').test(outsideConfig)) continue; // path 2
    if (outsideConfig.includes('"' + key + '"')) continue; // path 3
    deadKeys.push(key);
  }

  return {
    deadKeys,
    unreadComponents,
    totals: { keys: declared.size, components: components.size },
  };
}
