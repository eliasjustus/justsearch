import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function parseArgs(argv) {
  const out = { outFile: null };
  for (const arg of argv) {
    if (arg.startsWith('--out-file=')) {
      out.outFile = arg.slice('--out-file='.length);
    }
  }
  if (!out.outFile) {
    throw new Error('Missing required --out-file=PATH');
  }
  return out;
}

function toPosix(p) {
  return String(p).replace(/\\/g, '/');
}

async function existsDir(p) {
  try {
    const st = await fs.stat(p);
    return st.isDirectory();
  } catch {
    return false;
  }
}

async function walkJavaFiles(dirAbs, out) {
  const entries = await fs.readdir(dirAbs, { withFileTypes: true });
  for (const ent of entries) {
    const abs = path.join(dirAbs, ent.name);
    if (ent.isDirectory()) {
      await walkJavaFiles(abs, out);
    } else if (ent.isFile() && ent.name.endsWith('.java')) {
      out.push(abs);
    }
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  const __filename = fileURLToPath(import.meta.url);
  const __dirname = path.dirname(__filename);
  const repoRoot = path.resolve(__dirname, '..', '..');
  const modulesDir = path.join(repoRoot, 'modules');
  if (!(await existsDir(modulesDir))) {
    throw new Error(`modules/ not found at ${modulesDir}`);
  }

  const legacyRe = /^\s*import\s+io\.justsearch\.ipc\.(?!v1\.)[^;]+;/gm;
  const v1Re = /^\s*import\s+io\.justsearch\.ipc\.v1\.[^;]+;/gm;

  const byModule = {};
  const legacyFiles = [];
  const v1Files = [];

  const moduleNames = (await fs.readdir(modulesDir, { withFileTypes: true }))
    .filter((e) => e.isDirectory())
    .map((e) => e.name)
    .sort((a, b) => a.localeCompare(b));

  for (const moduleName of moduleNames) {
    const srcMainJava = path.join(modulesDir, moduleName, 'src', 'main', 'java');
    if (!(await existsDir(srcMainJava))) {
      continue;
    }

    const javaFiles = [];
    await walkJavaFiles(srcMainJava, javaFiles);

    let moduleLegacy = [];
    let moduleV1 = [];
    for (const abs of javaFiles) {
      const text = await fs.readFile(abs, 'utf8').catch(() => '');
      const legacyMatches = text.match(legacyRe) || [];
      const v1Matches = text.match(v1Re) || [];
      const rel = toPosix(path.relative(repoRoot, abs));
      if (legacyMatches.length > 0) moduleLegacy.push(rel);
      if (v1Matches.length > 0) moduleV1.push(rel);
    }

    moduleLegacy = Array.from(new Set(moduleLegacy)).sort((a, b) => a.localeCompare(b));
    moduleV1 = Array.from(new Set(moduleV1)).sort((a, b) => a.localeCompare(b));

    if (moduleLegacy.length > 0 || moduleV1.length > 0) {
      byModule[moduleName] = {
        legacy_file_count: moduleLegacy.length,
        v1_file_count: moduleV1.length,
        legacy_files: moduleLegacy,
        v1_files: moduleV1,
      };
    }

    legacyFiles.push(...moduleLegacy);
    v1Files.push(...moduleV1);
  }

  const payload = {
    schema_version: 1,
    legacy: {
      file_count: Array.from(new Set(legacyFiles)).length,
      files: Array.from(new Set(legacyFiles)).sort((a, b) => a.localeCompare(b)),
    },
    v1: {
      file_count: Array.from(new Set(v1Files)).length,
      files: Array.from(new Set(v1Files)).sort((a, b) => a.localeCompare(b)),
    },
    by_module: byModule,
  };

  const outAbs = path.isAbsolute(args.outFile) ? args.outFile : path.join(process.cwd(), args.outFile);
  await fs.mkdir(path.dirname(outAbs), { recursive: true });
  await fs.writeFile(outAbs, JSON.stringify(payload, null, 2) + '\n', 'utf8');
  process.stdout.write(outAbs + '\n');
}

await main();


