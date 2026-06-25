#!/usr/bin/env node
/**
 * Resolve affected Gradle modules from dorny/paths-filter output.
 *
 * Takes a JSON array of changed filter names (from paths-filter output)
 * and expands them to include transitive dependents using the module
 * dependency graph.
 *
 * Usage:
 *   node scripts/ci/resolve-affected-modules.mjs --changed '["core","search"]'
 *   node scripts/ci/resolve-affected-modules.mjs --changed '["all"]'
 *
 * Output (stdout): space-separated Gradle task paths, e.g.:
 *   :modules:core:test :modules:reranker:test :modules:adapters-lucene:test
 *
 * If "all" is in the changed list, outputs tasks for ALL modules.
 * If no modules changed, outputs nothing (empty string).
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ROOT = path.resolve(__dirname, '..', '..');

// --- Parse CLI args ---
const changedArg = process.argv.find(a => a.startsWith('--changed'));
let changedRaw = '';
if (changedArg) {
  const eqIdx = changedArg.indexOf('=');
  if (eqIdx !== -1) {
    changedRaw = changedArg.slice(eqIdx + 1);
  } else {
    const nextIdx = process.argv.indexOf(changedArg) + 1;
    changedRaw = process.argv[nextIdx] || '';
  }
}

let changedModules;
try {
  changedModules = JSON.parse(changedRaw);
} catch {
  console.error(`Failed to parse --changed JSON: ${changedRaw}`);
  process.exit(1);
}

if (!Array.isArray(changedModules)) {
  console.error(`--changed must be a JSON array, got: ${typeof changedModules}`);
  process.exit(1);
}

// --- Parse settings.gradle.kts for module list ---
function parseSettingsGradle() {
  const settingsPath = path.join(ROOT, 'settings.gradle.kts');
  const text = fs.readFileSync(settingsPath, 'utf8');
  const includeMatch = text.match(/include\s*\(\s*([\s\S]*?)\s*\)/);
  if (!includeMatch) return [];
  return [...includeMatch[1].matchAll(/"([^"]+)"/g)]
    .map(m => m[1])
    .filter(p => p.startsWith(':modules:'))
    .map(p => p.replace(':modules:', ''));
}

// --- Parse dependency edges from build.gradle.kts files ---
function parseDependencyEdges(allModules) {
  const edges = [];
  const PRODUCTION_CONFIGS = new Set(['api', 'implementation', 'runtimeOnly']);
  for (const mod of allModules) {
    const buildFile = path.join(ROOT, 'modules', mod, 'build.gradle.kts');
    if (!fs.existsSync(buildFile)) continue;
    const text = fs.readFileSync(buildFile, 'utf8');
    // Matches both direct project() calls and wrapped calls like testFixtures(project(...))
    const re = /(api|implementation|runtimeOnly|testImplementation|testRuntimeOnly|integrationTestImplementation)\s*\([^)]*project\s*\(\s*["'](:modules:[^"']+)["']\s*\)/g;
    for (const m of text.matchAll(re)) {
      const configType = m[1];
      const depPath = m[2].replace(':modules:', '');
      if (PRODUCTION_CONFIGS.has(configType)) {
        edges.push({ from: mod, to: depPath });
      }
    }
  }
  return edges;
}

// --- Build reverse dependency map ---
function calculateDependents(edges) {
  const dependents = new Map();
  for (const { from, to } of edges) {
    if (!dependents.has(to)) dependents.set(to, []);
    dependents.get(to).push(from);
  }
  return dependents;
}

// --- Expand changed modules to include transitive dependents ---
function expandDependents(changedModules, dependents) {
  const affected = new Set(changedModules);
  const queue = [...changedModules];
  while (queue.length > 0) {
    const mod = queue.shift();
    for (const dep of (dependents.get(mod) || [])) {
      if (!affected.has(dep)) {
        affected.add(dep);
        queue.push(dep);
      }
    }
  }
  return affected;
}

// --- Main ---
const allModules = parseSettingsGradle();

// If 'all' is in the changed list, test everything
if (changedModules.includes('all')) {
  const tasks = allModules.map(m => `:modules:${m}:test`).join(' ');
  process.stdout.write(tasks);
  process.exit(0);
}

// Filter to only valid module names
const validChanged = changedModules.filter(m => allModules.includes(m));
if (validChanged.length === 0) {
  // No modules changed — nothing to test
  process.exit(0);
}

const edges = parseDependencyEdges(allModules);
const dependents = calculateDependents(edges);
const affected = expandDependents(validChanged, dependents);

// Output as space-separated Gradle task paths
const tasks = [...affected]
  .filter(m => allModules.includes(m))
  .map(m => `:modules:${m}:test`)
  .join(' ');
process.stdout.write(tasks);
