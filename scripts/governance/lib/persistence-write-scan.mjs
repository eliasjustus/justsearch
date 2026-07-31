import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';

const WALK_EXCLUDES = new Set(['build', 'node_modules', 'dist', '.git', '.gradle', 'target', 'tmp']);
const SOURCE_EXTENSIONS = new Set(['.java', '.rs']);

// Write-creating idioms only. `FileChannel.open` and `new RandomAccessFile` are deliberately
// excluded: both are mode-dependent (`StandardOpenOption.READ`, `"r"`) and matching them by name
// flags read-only sites — the SQLite header preflight and the diagnostics log tail both are.
// A false positive here is worse than a gap, because clearing it means adding a read-only file to
// `nonDurableWriteSites`, i.e. registering false authority. Detecting their write modes needs
// argument analysis this scanner deliberately does not do.
const JAVA_MUTATION =
  /\b(?:Files\.(?:write|writeString|newOutputStream|newBufferedWriter|createFile|move|copy|delete|deleteIfExists)|AtomicFileWrites\.(?:replace|replaceUtf8)|DriverManager\.getConnection|RrdDb|RrdDef)\b|\bnew\s+(?:FileOutputStream|FileWriter|PrintWriter|ObjectOutputStream)\b/;
const RUST_MUTATION =
  /\b(?:std::fs|fs)::(?:write|rename|copy|remove_file|remove_dir_all|create_dir_all)\b|\bOpenOptions::new\b/;
const DURABLE_ANCHOR =
  /\b(?:dataDir|data_dir|app_data_dir|aiHome|statusPath|rootDir|indexBasePath|dbPath|crashDir|telemetry|runtimeDir|modelsDir|native_bin|nativeBin|jdbc:sqlite|\.rrd\b|StoreCatalog)\b/i;

/**
 * Classify a production source as a persistence write site.
 *
 * Unlike the retired `*Store.java`/`Path`-constructor heuristic, this examines Java and Rust
 * mutation calls and does not depend on naming or constructor shape. The durable-anchor condition
 * keeps benchmark outputs and arbitrary user-selected export destinations out of this gate; those
 * remain governed by their own operation surfaces.
 */
export function isPersistenceWriteSource(sourcePath, source) {
  const normalized = norm(sourcePath);
  const extension = extname(normalized);
  if (!SOURCE_EXTENSIONS.has(extension)) return false;
  if (
    extension === '.java'
      ? !normalized.includes('/src/main/')
      : !normalized.includes('/src-tauri/src/')
  ) {
    return false;
  }
  const mutation = extension === '.rs' ? RUST_MUTATION : JAVA_MUTATION;
  return mutation.test(source) && DURABLE_ANCHOR.test(source);
}

export function scanPersistenceWriteSites(root) {
  const modules = resolve(root, 'modules');
  if (!existsSync(modules)) return [];
  const out = [];
  for (const absolute of walk(modules)) {
    const rel = norm(relative(root, absolute));
    const source = readFileSync(absolute, 'utf8');
    if (isPersistenceWriteSource(rel, source)) out.push(rel);
  }
  return out.sort();
}

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (WALK_EXCLUDES.has(entry.name)) continue;
    const absolute = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(absolute));
    else if (SOURCE_EXTENSIONS.has(extname(entry.name))) out.push(absolute);
  }
  return out;
}

function norm(value) {
  return String(value ?? '').replace(/\\/g, '/');
}
