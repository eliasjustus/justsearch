#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on frontend files.
 *
 * When an agent edits a file in modules/ui-web/src/**\/*.{ts,tsx}, this hook
 * outputs additionalContext telling the agent which ui-shot steps are affected
 * and how to capture them.
 *
 * - Synchronous (blocks until return, <50ms)
 * - No external process spawning — just a JSON lookup
 * - Outputs hookSpecificOutput.additionalContext when matches found
 */

import fs from 'node:fs';
import path from 'node:path';

// Load file-to-step index from shared JSON (single source of truth).
// Both this hook and jseval/ui_shot.py read the same file.
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..', '..');
const indexPath = path.join(repoRoot, 'scripts', 'jseval', 'jseval', 'ui_step_index.json');

let FILE_TO_STEPS = {};
try {
  FILE_TO_STEPS = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
} catch {
  // Index not found — hook is a no-op
}

function normalize(p) {
  return p.replace(/\\/g, '/');
}

function findAffectedSteps(filePath) {
  const norm = normalize(filePath);
  const matched = new Set();

  for (const [pattern, steps] of Object.entries(FILE_TO_STEPS)) {
    if (norm.endsWith(pattern) || norm.includes(pattern)) {
      for (const s of steps) matched.add(s);
    }
  }
  return [...matched];
}

function isFrontendFile(filePath) {
  const norm = normalize(filePath);
  return norm.includes('modules/ui-web/src/') &&
    (norm.endsWith('.ts') || norm.endsWith('.tsx'));
}

// Tempdoc 615 §37.2 (lever c — discoverability): ui-shot is hook-pushed, but the five other
// measurement verbs (ui-a11y-gate / ui-diff / ui-critic / ui-fuzz / --trace) rot unused because
// nothing surfaces them. Map the EDIT CONTEXT to the contextually-relevant verb so the agent learns
// the right instrument at the moment it matters — without a new mechanism (same hook, same lookup).

// A style/token/theme/presentation edit is the canonical "did I break a11y or the design system?"
// moment. Fires even when the file maps to NO ui-shot step (style files often aren't in the index) —
// that is exactly when surfacing the gate/critic is pure gain, since there's no ui-shot hint for them.
function styleHints(filePath) {
  const norm = normalize(filePath);
  const styleish = /\.styles\.ts$/.test(norm) ||
    /\/(themes|styles)\//.test(norm) ||
    /(token|theme|presentation|contrast|color)/i.test(norm.split('/').pop() || '');
  if (!styleish) return [];
  return [
    'Style/token/theme edit — check a11y closure + the design system:',
    '  jseval ui-a11y-gate          # fail on a NEW axe violation vs baseline',
    '  jseval ui-critic <step>       # grounded design-reference critique prompt',
  ];
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    const toolName = input.tool_name;
    const toolInput = input.tool_input;

    if (toolName !== 'Edit' && toolName !== 'Write') return;

    const filePath = toolInput?.file_path;
    if (!filePath || !isFrontendFile(filePath)) return;

    const steps = findAffectedSteps(filePath);
    const styles = styleHints(filePath);
    // Nothing to say only when the file maps to no step AND is not a style/theme edit.
    if (steps.length === 0 && styles.length === 0) return;

    // Build the hint message
    const shortPath = normalize(filePath).split('modules/ui-web/src/')[1] || filePath;
    const stepLines = steps.length > 0
      ? [
          `Affected steps: ${steps.slice(0, 5).join(', ')}${steps.length > 5 ? ` (+${steps.length - 5} more)` : ''}`,
          `To see the visual result: jseval ui-shot ${steps[0]}`,
          steps.length > 1 ? `To capture all: jseval ui-shot --affected "${filePath}"` : '',
          // A covered-surface edit is a candidate for an unintended-change check (615 §37.2).
          'To confirm no unintended visual change: capture before + after, then',
          '  jseval ui-diff <before.measure.json> <after.measure.json>',
        ]
      : [];

    const hint = [
      `UI file edited: ${shortPath}`,
      ...stepLines,
      ...styles,
    ].filter(Boolean).join('\n');

    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext: hint,
      },
    }));
  } catch {
    // Parse failure — no output, don't block
  }
}

main().catch(() => process.exit(0));
