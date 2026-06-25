/**
 * Tempdoc 561 surface tier — integration tests for the interaction-surface enforcer. Proves each
 * rule FIRES in its target scenario (the wrong-gate lesson: prove the gate fires in the target
 * scenario, not just that it exists) and stays quiet when the tree is clean.
 *
 * Run with: `node scripts/governance/gates/interaction-surface/enforcer.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { enforceInteractionSurface } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const GATE = { config: { register: 'governance/interaction-surfaces.v1.json' } };

const REGISTER = {
  version: 1,
  canonicalSurface: 'core.unified-chat-surface',
  canonicalMountTag: 'jf-unified-chat-view',
  visiblePlacements: ['RAIL', 'STAGE'],
  scan: {
    surfaceCatalog: 'catalog/CoreSurfaceCatalog.java',
    shapeCatalog: 'catalog/CoreConversationShapeCatalog.java',
    feMirror: 'fe/coreInteractionShapes.ts',
    feRoots: ['fe'],
    feExcludeSuffixes: ['.test.ts'],
  },
};

const SHAPE_CATALOG = `package fixture;
import java.util.Set;
public final class CoreConversationShapeCatalog {
  public static final Set<String> CORE_USER_INTERACTION_SHAPES =
      Set.of("core.rag-ask", "core.free-chat", "core.extract", "core.agent-run");
}
`;

const FE_MIRROR = `export const CORE_INTERACTION_SHAPES = [
  'core.rag-ask', 'core.free-chat', 'core.extract', 'core.agent-run',
] as const;
export const ONE_WINDOW_MOUNT_TAG = 'jf-unified-chat-view';
`;

const FE_REGS_OK = `import { ONE_WINDOW_MOUNT_TAG } from './coreInteractionShapes.js';
registerViewFactory('core.rag-ask', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.free-chat', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.extract', ONE_WINDOW_MOUNT_TAG);
registerViewFactory('core.agent-run', 'jf-unified-chat-view');
`;

/** Build a surface declaration block. shapes = array of SHAPE_* const names. */
function surfaceBlock(idConst, audience, placement, shapeConsts, mountTag) {
  const shapes = shapeConsts.join(', ');
  return `        new Surface(
            ${idConst},
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.${audience},
            Placement.${placement},
            new SurfaceConsumes(
                Set.of(), Set.of(), Set.of(), Set.of(),
                /* conversationShapes */ Set.of(${shapes})),
            "${mountTag}")`;
}

/** Assemble a CoreSurfaceCatalog.java from refs + surface blocks. */
function surfaceCatalog({ surfaceRefs, blocks }) {
  const refLines = Object.entries(surfaceRefs)
    .map(([c, v]) => `  public static final SurfaceRef ${c} = new SurfaceRef("${v}");`)
    .join('\n');
  const shapeRefs = [
    ['SHAPE_RAG_ASK', 'core.rag-ask'],
    ['SHAPE_FREE_CHAT', 'core.free-chat'],
    ['SHAPE_EXTRACT', 'core.extract'],
    ['SHAPE_AGENT_RUN', 'core.agent-run'],
  ]
    .map(([c, v]) => `  private static final ConversationShapeRef ${c} = new ConversationShapeRef("${v}");`)
    .join('\n');
  return `package fixture;
public final class CoreSurfaceCatalog {
${refLines}
${shapeRefs}
  static java.util.List<Surface> defs() {
    return java.util.List.of(
${blocks.join(',\n')});
  }
}
`;
}

const ALL_SHAPES = ['SHAPE_RAG_ASK', 'SHAPE_FREE_CHAT', 'SHAPE_EXTRACT', 'SHAPE_AGENT_RUN'];

// The canonical one window: USER × RAIL consuming all four core shapes.
const UNIFIED_BLOCK = surfaceBlock('UNIFIED', 'USER', 'RAIL', ALL_SHAPES, 'jf-unified-chat-view');
const UNIFIED_REF = { UNIFIED: 'core.unified-chat-surface' };

function scaffold({ surfaceCatalogSrc, shapeCatalog = SHAPE_CATALOG, feMirror = FE_MIRROR, feRegs = FE_REGS_OK }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'interaction-surface-'));
  tmpDirs.push(root);
  const write = (rel, content) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  };
  write('governance/interaction-surfaces.v1.json', JSON.stringify(REGISTER, null, 2));
  write('catalog/CoreSurfaceCatalog.java', surfaceCatalogSrc);
  write('catalog/CoreConversationShapeCatalog.java', shapeCatalog);
  write('fe/coreInteractionShapes.ts', feMirror);
  write('fe/UnifiedChatView.ts', feRegs);
  return root;
}

async function enforce(fixtureRoot) {
  return enforceInteractionSurface({ repoRoot: fixtureRoot, gate: GATE, fixtureMode: true, fixtureRoot });
}

async function run(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const ruleIds = (r) => r.findings.map((f) => f.ruleId);

// 1. Clean one-window tree → pass.
await run('canonical one window only → pass (guard quiet when clean)', async () => {
  const src = surfaceCatalog({
    surfaceRefs: { ...UNIFIED_REF, ASK: 'core.ask-surface' },
    blocks: [
      UNIFIED_BLOCK,
      surfaceBlock('ASK', 'USER', 'DEEPLINK', ['SHAPE_RAG_ASK'], 'jf-chat-shape-mount'),
    ],
  });
  const r = await enforce(scaffold({ surfaceCatalogSrc: src }));
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
});

// 2. THE invariant: a second visible interaction surface → fail.
await run('second visible (RAIL) interaction surface → fail (multiple-visible)', async () => {
  const src = surfaceCatalog({
    surfaceRefs: { ...UNIFIED_REF, AGENT: 'core.agent-surface' },
    blocks: [UNIFIED_BLOCK, surfaceBlock('AGENT', 'USER', 'RAIL', ['SHAPE_AGENT_RUN'], 'jf-agent-surface')],
  });
  const r = await enforce(scaffold({ surfaceCatalogSrc: src }));
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(
    ruleIds(r).includes('interaction-surface/multiple-visible-interaction-surfaces'),
    `(${ruleIds(r).join(',')})`,
  );
});

// 3. A DEEPLINK second surface consuming a core shape → still pass (exempt — routes into one window).
await run('DEEPLINK second surface with a core shape → pass (exempt)', async () => {
  const src = surfaceCatalog({
    surfaceRefs: { ...UNIFIED_REF, EXTRACT: 'core.extract-surface' },
    blocks: [UNIFIED_BLOCK, surfaceBlock('EXTRACT', 'USER', 'DEEPLINK', ['SHAPE_EXTRACT'], 'jf-chat-shape-mount')],
  });
  const r = await enforce(scaffold({ surfaceCatalogSrc: src }));
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
});

// 4. The one window is missing entirely → fail (canonical-window-missing).
await run('no canonical window present → fail (canonical-window-missing)', async () => {
  const src = surfaceCatalog({
    surfaceRefs: { ASK: 'core.ask-surface' },
    blocks: [surfaceBlock('ASK', 'USER', 'DEEPLINK', ['SHAPE_RAG_ASK'], 'jf-chat-shape-mount')],
  });
  const r = await enforce(scaffold({ surfaceCatalogSrc: src }));
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('interaction-surface/canonical-window-missing'), `(${ruleIds(r).join(',')})`);
});

// 5. The one window does not consume a core shape → fail (uncovered-core-shape).
await run('a core shape not consumed by the one window → fail (uncovered-core-shape)', async () => {
  const partial = surfaceBlock('UNIFIED', 'USER', 'RAIL', ['SHAPE_RAG_ASK', 'SHAPE_FREE_CHAT', 'SHAPE_EXTRACT'], 'jf-unified-chat-view');
  const src = surfaceCatalog({ surfaceRefs: { ...UNIFIED_REF }, blocks: [partial] });
  const r = await enforce(scaffold({ surfaceCatalogSrc: src }));
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('interaction-surface/uncovered-core-shape'), `(${ruleIds(r).join(',')})`);
});

// 6. The FE mirror drifts from the Java authority → fail (fe-mirror-drift).
await run('FE mirror omits a core shape → fail (fe-mirror-drift)', async () => {
  const src = surfaceCatalog({ surfaceRefs: { ...UNIFIED_REF }, blocks: [UNIFIED_BLOCK] });
  const driftedMirror = `export const CORE_INTERACTION_SHAPES = ['core.rag-ask', 'core.free-chat', 'core.extract'] as const;`;
  const r = await enforce(scaffold({ surfaceCatalogSrc: src, feMirror: driftedMirror }));
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('interaction-surface/fe-mirror-drift'), `(${ruleIds(r).join(',')})`);
});

// 7. A reintroduced separate per-shape FE view → fail (second-interaction-view).
await run('a core shape registers a NON-one-window view tag → fail (second-interaction-view)', async () => {
  const src = surfaceCatalog({ surfaceRefs: { ...UNIFIED_REF }, blocks: [UNIFIED_BLOCK] });
  const forkRegs = FE_REGS_OK + `\nregisterViewFactory('core.rag-ask', 'jf-ask-view');\n`;
  const r = await enforce(scaffold({ surfaceCatalogSrc: src, feRegs: forkRegs }));
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('interaction-surface/second-interaction-view'), `(${ruleIds(r).join(',')})`);
});

// 8. Missing register → fail (register-missing).
await run('missing register → fail (register-missing)', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'interaction-surface-'));
  tmpDirs.push(root);
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('interaction-surface/register-missing'), `(${ruleIds(r).join(',')})`);
});

for (const d of tmpDirs) {
  try {
    fs.rmSync(d, { recursive: true, force: true });
  } catch {
    // best-effort cleanup
  }
}

if (failures.length > 0) {
  console.error(`interaction-surface enforcer tests: ${passed} passed, ${failures.length} FAILED`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`interaction-surface enforcer tests: ${passed} passed`);
