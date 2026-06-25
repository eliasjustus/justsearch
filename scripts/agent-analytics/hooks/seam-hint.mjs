#!/usr/bin/env node

/**
 * PostToolUse hook — the authoring-oracle for the logic-seams register (tempdoc 555 §5).
 *
 * Fires ONLY when a NEW/replaced production Java class (a `Write`, not every `Edit`) lands in a
 * seam-bearing module AND looks plausibly law-bearing — i.e. it is branch/arithmetic-DENSE and
 * carries no IO/native/plumbing markers (a light purity heuristic). It then surfaces "is this a
 * law-bearing seam? declare it" so dense pure logic can't silently ship test-poor (the §1 defect),
 * without nagging on the ~50 ordinary files per seam-module (the original hook's noise problem).
 *
 * Advisory only; the test-efficacy gate enforces the declared set. Synchronous, fast, never blocks.
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const normalize = p => p.replace(/\\/g, '/');

/** modules/<m>/src/main/java/<pkg>/<Class>.java → {module, fqcn} (null if not prod Java). */
function parseProdJava(filePath) {
  const m = normalize(filePath).match(/modules\/([^/]+)\/src\/main\/java\/(.+)\.java$/);
  return m ? { module: m[1], fqcn: m[2].replace(/\//g, '.') } : null;
}

// Markers that disqualify a class from being a pure law-seam (IO / native / clock / plumbing / web).
const IMPURITY = [
  /\bFiles\./, /FileChannel/, /\bPath\b.*\b(read|write)/, /import java\.nio\.file/,
  /IndexWriter|IndexReader|IndexSearcher|Directory\b/, /OrtSession|ai\.onnxruntime/,
  /\bgrpc\b|Grpc[A-Z]/, /System\.(currentTimeMillis|nanoTime)/, /Instant\.now|Clock\b/,
  /@RestController|Javalin|io\.javalin|HttpServlet/, /\bThread\b|ExecutorService|CompletableFuture/,
  /Logger|LoggerFactory/,
];

/** Rough branch/arithmetic density: conditionals, comparisons, boolean ops, arithmetic. */
function branchDensity(src) {
  const count = re => (src.match(re) || []).length;
  return (
    count(/\bif\s*\(/g) +
    count(/\?[^.]/g) + // ternary (avoid ?. )
    count(/(>=|<=|==|!=|[<>])/g) +
    count(/&&|\|\|/g) +
    count(/[ (]\w+\s*[+\-*/]\s*\w+/g) // simple binary arithmetic
  );
}

async function main() {
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  let input;
  try {
    input = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    return;
  }

  // Only a Write — a class being born/replaced. Edits of existing files rarely create a new seam.
  if (input.tool_name !== 'Write') return;
  const filePath = input.tool_input?.file_path;
  const content = input.tool_input?.content;
  if (!filePath || typeof content !== 'string') return;

  const parsed = parseProdJava(filePath);
  if (!parsed) return;

  const registryPath = resolve(process.cwd(), 'governance/logic-seams.v1.json');
  if (!existsSync(registryPath)) return;
  let register;
  try {
    register = JSON.parse(readFileSync(registryPath, 'utf8'));
  } catch {
    return;
  }
  const seams = register.seams ?? [];
  const seamModules = new Set(
    seams.map(s => (s.gradlePath ?? '').split(':').filter(Boolean).slice(-1)[0]).filter(Boolean),
  );
  const registeredClasses = new Set(seams.map(s => s.targetClass).filter(Boolean));

  if (!seamModules.has(parsed.module)) return; // only seam-bearing modules
  if (registeredClasses.has(parsed.fqcn)) return; // already a seam
  if (IMPURITY.some(re => re.test(content))) return; // not pure → not a law-seam (Wall 2)
  if (branchDensity(content) < 8) return; // not dense enough to be law-bearing

  const hint = [
    `seam check (tempdoc 555): new dense, IO-free class ${parsed.fqcn} in a seam-bearing module.`,
    `If its failure mode is a silent WRONG VALUE (scoring, precedence, offsets, budgets, state guards),`,
    `declare it in governance/logic-seams.v1.json with its law + guard test so the test-efficacy gate`,
    `measures whether its tests actually bite. If it has no such law, ignore — it stays out by design.`,
  ].join('\n');

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: hint },
    }),
  );
}

main().catch(() => process.exit(0));
