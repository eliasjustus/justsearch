/**
 * Reproducible run-level repository facts for the governance history.
 *
 * Units are deliberately narrow:
 * - gradleModuleCount: distinct `:modules:*` includes in settings.gradle.kts;
 * - testFileCount: source files in named JVM test source sets plus JS/TS *.test|*.spec files;
 * - productionSourceLocByModule: physical lines (including blanks/comments) in production source
 *   roots, excluding generated/build/vendor trees and JS/TS test/spec files.
 *
 * These facts are not gate baselines and are never aggregated with heterogeneous ratchet pins.
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';

const SOURCE_EXTENSIONS = new Set([
  '.java', '.kt', '.kts', '.rs', '.ts', '.tsx', '.js', '.mjs', '.cjs', '.py', '.ps1',
]);
const EXCLUDED_DIRS = new Set(['build', 'generated', 'node_modules', 'dist', 'target', '.gradle']);
const JVM_TEST_SOURCE_SETS = new Set([
  'test', 'testFixtures', 'integrationTest', 'systemTest', 'soakTest',
]);
const JS_TEST_FILE = /\.(?:test|spec)\.(?:[cm]?js|tsx?)$/;

function walkFiles(root) {
  if (!existsSync(root)) return [];
  const out = [];
  const visit = dir => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory() && EXCLUDED_DIRS.has(entry.name)) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) visit(full);
      else if (entry.isFile()) out.push(full);
    }
  };
  visit(root);
  return out;
}

function physicalLines(text) {
  if (text.length === 0) return 0;
  return text.split(/\r?\n/).length - (text.endsWith('\n') ? 1 : 0);
}

function gradleModules(repoRoot) {
  const settings = resolve(repoRoot, 'settings.gradle.kts');
  if (!existsSync(settings)) return [];
  const matches = readFileSync(settings, 'utf8').matchAll(/["'](:modules:[^"']+)["']/g);
  return [...new Set([...matches].map(m => m[1]))].sort();
}

function isTestFile(repoRoot, file) {
  const rel = relative(repoRoot, file).replaceAll('\\', '/');
  if (JS_TEST_FILE.test(rel)) return true;
  const sourceSet = rel.match(/^modules\/[^/]+\/src\/([^/]+)\//)?.[1];
  return JVM_TEST_SOURCE_SETS.has(sourceSet);
}

function productionSourceRoots(repoRoot, moduleName) {
  if (moduleName === 'ui-web') return [resolve(repoRoot, 'modules/ui-web/src')];
  if (moduleName === 'shell') return [
    resolve(repoRoot, 'modules/shell/src-tauri/src'),
    resolve(repoRoot, 'modules/shell/src'),
  ];
  return [resolve(repoRoot, `modules/${moduleName}/src/main`)];
}

export function collectRepositoryHealth(repoRoot) {
  const modulesRoot = resolve(repoRoot, 'modules');
  const moduleDirs = existsSync(modulesRoot)
    ? readdirSync(modulesRoot, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => entry.name)
        .sort()
    : [];
  const allModuleFiles = moduleDirs.flatMap(name => walkFiles(resolve(modulesRoot, name)));
  const testFileCount = allModuleFiles.filter(file => SOURCE_EXTENSIONS.has(extname(file)) && isTestFile(repoRoot, file)).length;
  const productionSourceLocByModule = {};
  for (const moduleName of moduleDirs) {
    const files = productionSourceRoots(repoRoot, moduleName)
      .flatMap(walkFiles)
      .filter(file => SOURCE_EXTENSIONS.has(extname(file)) && !JS_TEST_FILE.test(file));
    if (files.length === 0) continue;
    productionSourceLocByModule[moduleName] = files.reduce(
      (sum, file) => sum + physicalLines(readFileSync(file, 'utf8')),
      0,
    );
  }
  return {
    units: {
      gradleModules: 'distinct :modules:* project paths in settings.gradle.kts',
      testFiles: 'source files in JVM test/testFixtures/integrationTest/systemTest/soakTest sets plus JS/TS *.test|*.spec files',
      productionSourceLoc: 'physical lines including blanks/comments in production source roots',
    },
    gradleModuleCount: gradleModules(repoRoot).length,
    testFileCount,
    productionSourceLocByModule,
  };
}
