#!/usr/bin/env node
/**
 * thread-event-kinds check — tempdoc S4a (Search Thread, risk-review finding #1 follow-up).
 *
 * The unified-thread event-kind vocabulary is ONE closed authority: the backend declares it as the
 * `InteractionEventKind` Java enum, and the FE `unifiedThreadClient.ts` mirrors it as
 * `KNOWN_EVENT_KINDS` (the Zod enum driving strict per-event parsing). Before S4a's forward-tolerant
 * parsing landed, an FE build unaware of a new backend kind (e.g. a not-yet-shipped `SEARCH` kind)
 * would blank the whole thread; S4a fixed that failure mode, but the two vocabularies can still DRIFT
 * silently (a new Java member the FE never mirrors just always degrades to the generic `UNKNOWN` item —
 * legal, but likely unintentional; a stale FE entry with no Java member is dead/typo'd). This check
 * makes that drift visible:
 *
 *  - FORWARD: every `InteractionEventKind` Java enum member appears in the FE's `KNOWN_EVENT_KINDS`.
 *  - BACKWARD: every FE `KNOWN_EVENT_KINDS` entry is a real `InteractionEventKind` member.
 *
 * Not wired into CI yet (registration is deferred to the backend SEARCH-kind follow-up stage) — this
 * script only needs to pass standalone: `node scripts/ci/check-thread-event-kinds.mjs`.
 */
import { readFileSync } from 'node:fs';

const JAVA_ENUM_FILE =
  'modules/app-agent-api/src/main/java/io/justsearch/agent/api/interaction/InteractionEventKind.java';
const FE_CLIENT_FILE = 'modules/ui-web/src/shell-v0/views/unifiedThreadClient.ts';

/** Strip Java block/line comments so enum-constant extraction can't false-positive on prose. */
function stripJavaComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
}

/** Extract the bare `IDENT,` enum-constant names from `enum InteractionEventKind { ... }`. */
export function extractJavaEnumKinds(javaSrc) {
  const withoutComments = stripJavaComments(javaSrc);
  const start = withoutComments.indexOf('enum InteractionEventKind');
  const slice = start >= 0 ? withoutComments.slice(start) : withoutComments;
  const braceStart = slice.indexOf('{');
  const braceEnd = slice.indexOf('}');
  const region = braceStart >= 0 && braceEnd > braceStart ? slice.slice(braceStart + 1, braceEnd) : '';
  const kinds = new Set();
  const re = /\b([A-Z][A-Z0-9_]*)\b/g;
  let m;
  while ((m = re.exec(region)) !== null) kinds.add(m[1]);
  return kinds;
}

/** Extract the FE's `KNOWN_EVENT_KINDS` string-literal array entries from unifiedThreadClient.ts. */
export function extractFeKnownKinds(tsSrc) {
  const start = tsSrc.indexOf('KNOWN_EVENT_KINDS');
  const slice = start >= 0 ? tsSrc.slice(start) : tsSrc;
  const end = slice.indexOf('] as const');
  const region = end >= 0 ? slice.slice(0, end) : slice;
  const kinds = new Set();
  const re = /'([A-Z][A-Z0-9_]*)'/g;
  let m;
  while ((m = re.exec(region)) !== null) kinds.add(m[1]);
  return kinds;
}

/** Pure correspondence check. Returns an array of failure strings (empty = pass). */
export function checkCorrespondence({ javaKinds, feKinds }) {
  const failures = [];

  for (const kind of javaKinds) {
    if (!feKinds.has(kind)) {
      failures.push(
        `forward: InteractionEventKind member \`${kind}\` has no FE KNOWN_EVENT_KINDS entry in ` +
          `unifiedThreadClient.ts — an FE build unaware of it degrades every \`${kind}\` event to the ` +
          `generic UNKNOWN item (S4a forward-tolerance, not a crash) but likely needs a real FE render. ` +
          `Add \`${kind}\` to KNOWN_EVENT_KINDS (and its case in unifiedThreadProjection.ts's KIND_MAP + ` +
          `UnifiedChatView's renderUnifiedItem), or confirm the generic degrade is intentional for now.`,
      );
    }
  }

  for (const kind of feKinds) {
    if (!javaKinds.has(kind)) {
      failures.push(
        `backward: FE KNOWN_EVENT_KINDS declares \`${kind}\`, which is not an InteractionEventKind Java ` +
          `enum member — a dead or mistyped FE entry. Remove it, or fix the Java enum if it's the typo.`,
      );
    }
  }

  return failures;
}

function main() {
  const javaKinds = extractJavaEnumKinds(readFileSync(JAVA_ENUM_FILE, 'utf8'));
  const feKinds = extractFeKnownKinds(readFileSync(FE_CLIENT_FILE, 'utf8'));

  if (javaKinds.size === 0 || feKinds.size === 0) {
    console.error(
      `✗ thread-event-kinds check FAILED: could not extract kinds ` +
        `(java=${javaKinds.size}, fe=${feKinds.size}) — one of the two seams moved; update this script.`,
    );
    process.exit(1);
  }

  const failures = checkCorrespondence({ javaKinds, feKinds });

  if (failures.length > 0) {
    console.error(
      '✗ thread-event-kinds check FAILED (tempdoc S4a):\n' + failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }

  console.log(
    `✓ thread-event-kinds check OK — InteractionEventKind↔KNOWN_EVENT_KINDS correspond ` +
      `(${javaKinds.size} Java enum members, ${feKinds.size} FE known kinds); no backend kind silently ` +
      `degrades unmirrored, and no FE entry is dead/mistyped.`,
  );
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-thread-event-kinds.mjs')) {
  main();
}
