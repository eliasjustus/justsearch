#!/usr/bin/env node
/**
 * Slice 491 §9.D Phase E C1 — CI gate for ConversationShape handler codegen.
 *
 * Default mode (no flags): runs `gen-shape-handlers.mjs --check`; non-zero
 * exit on any drift between the bundled fixture's expected output and the
 * committed `modules/ui-web/src/api/generated/shape-handlers/*.ts` files.
 * Catches the case where someone touches `BUNDLED_SHAPES` (or future: a
 * shape's eventSchema in Java) without committing the regenerated handler
 * files.
 *
 * Slice 491 F8 live mode: `--live [--port=N]` fetches `/api/registry/shapes`
 * from a running backend and asserts each Java shape's id + eventSchema
 * appears verbatim in `BUNDLED_SHAPES`. Catches the C0+C1 wrong-shape-ID
 * defect class (fix `ab548b97f`) — the static check passes when the fixture
 * is internally consistent, but the live check catches drift between the
 * fixture and the live Java catalog.
 *
 * Invoked by:
 *   npm run check:shape-handler-regen        # static mode (default)
 *   node scripts/ci/check-shape-handler-regen.mjs --live --port=33221
 *
 * Per §9.E A5: codegen is off-wire (no proto-contract dependency). The
 * static gate is a string-equality check between expected + committed
 * output; the live gate is a JSON comparison against the live catalog.
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-shape-handlers.mjs');

function parseArgs(argv) {
  const args = { live: false, port: 33221 };
  for (const a of argv.slice(2)) {
    if (a === '--live') args.live = true;
    else if (a.startsWith('--port=')) args.port = Number(a.slice(7));
  }
  return args;
}

async function liveCheck(port) {
  const url = `http://127.0.0.1:${port}/api/registry/shapes`;
  let res;
  try {
    res = await fetch(url);
  } catch (e) {
    console.error(`[check-shape-handler-regen --live] fetch failed: ${e}`);
    process.exit(1);
  }
  if (!res.ok) {
    console.error(
      `[check-shape-handler-regen --live] ${url} returned ${res.status} ${res.statusText}`,
    );
    process.exit(1);
  }
  const body = await res.json();
  if (!body || !Array.isArray(body.entries)) {
    console.error('[check-shape-handler-regen --live] response missing entries[]');
    process.exit(1);
  }
  const mod = await import(pathToFileURL(GEN_SCRIPT).href);
  const bundled = new Map(mod.BUNDLED_SHAPES.map((s) => [s.id, s.eventSchema]));

  const drift = [];
  for (const live of body.entries) {
    const liveSchema = live.eventSchema ?? [];
    const fixtureSchema = bundled.get(live.id);
    if (fixtureSchema === undefined) {
      drift.push(
        `Shape id '${live.id}' is in the live catalog but missing from BUNDLED_SHAPES`,
      );
      continue;
    }
    if (
      fixtureSchema.length !== liveSchema.length ||
      !fixtureSchema.every((e, i) => e === liveSchema[i])
    ) {
      drift.push(
        `Shape '${live.id}' eventSchema mismatch:\n  live=${JSON.stringify(liveSchema)}\n  fixture=${JSON.stringify(fixtureSchema)}`,
      );
    }
  }
  for (const [fixtureId] of bundled) {
    if (!body.entries.some((e) => e.id === fixtureId)) {
      drift.push(
        `Shape id '${fixtureId}' is in BUNDLED_SHAPES but missing from the live catalog`,
      );
    }
  }

  if (drift.length > 0) {
    console.error('[check-shape-handler-regen --live] DRIFT DETECTED:');
    for (const d of drift) console.error('  - ' + d);
    console.error(
      '\nFix: update scripts/codegen/gen-shape-handlers.mjs BUNDLED_SHAPES to match the live catalog,',
    );
    console.error('then re-run `node scripts/codegen/gen-shape-handlers.mjs` to regenerate handlers.');
    process.exit(1);
  }
  console.log(
    `[check-shape-handler-regen --live] passed — all ${body.entries.length} shape(s) match fixture.`,
  );
}

const args = parseArgs(process.argv);
if (args.live) {
  await liveCheck(args.port);
} else {
  const result = spawnSync('node', [GEN_SCRIPT, '--check'], {
    cwd: REPO_ROOT,
    stdio: 'inherit',
  });
  process.exit(result.status ?? 1);
}
