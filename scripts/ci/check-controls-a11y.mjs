#!/usr/bin/env node
/**
 * controls-a11y gate — tempdoc 559 Part II, Authority V (Operability).
 *
 * Every interactive affordance must be keyboard-operable, not mouse-only. The
 * structural fix is the one `<jf-control>` primitive (a native button → focus +
 * Enter/Space + role for free, accessible name projected from the declaration).
 * This gate makes the bad form fail the build: an activation handler
 * (`@click` / `@mousedown` / `@pointerdown`) bound to an element that is NOT
 * keyboard-operable by construction.
 *
 * An activation handler is OK iff its element is one of:
 *   (1) a natively-operable element (button / a / input / select / textarea / …);
 *   (2) the control primitive or any `*-button` custom element (jf-control,
 *       jf-op-button, jf-action-button, …);
 *   (3) the standalone-affordance triad — an interactive `role=` (button/menuitem/
 *       tab/switch/checkbox/radio/link) AND `tabindex` (plus a `@keydown`, by
 *       convention);
 *   (4) a row of a keyboard-navigable container — `role=` option/row/listitem/
 *       treeitem/gridcell/tab, OR `aria-selected`/`aria-current` present: the
 *       CONTAINER owns the arrow-key/Enter keyboard (the listbox/grid pattern,
 *       which axe also accepts; the §5 limit is this gate trusts the pattern);
 *   (5) a dismiss layer — `class` includes backdrop/scrim/overlay, or the handler
 *       is an `onBackdrop`/`dismiss`/`close` (decorative; keyboard path = Escape).
 * Otherwise it is a mouse-only affordance → fail.
 *
 * HONEST LIMIT (the 557 §5 ceiling): ESLint cannot inspect Lit-template `@click`
 * bindings, so this is a grep-grade scan over `<tag …@click…>` blocks (no `>` in
 * attribute values, which Lit templates respect). It catches the egregious
 * `<div @click>` class, not every conceivable evasion. Lighter scripts/ci tier;
 * wired as a ci.yml step + the CLAUDE.md pre-merge list.
 */
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, extname } from 'node:path';

const SRC = 'modules/ui-web/src';

// Natively keyboard-operable elements.
const NATIVE_OK = new Set([
  'button',
  'a',
  'input',
  'select',
  'textarea',
  'label',
  'summary',
  'details',
  'option',
  'dialog',
]);
// Operable visual atoms (tempdoc 574 atom tier): custom elements whose tag does NOT end in `-button`
// but which render a native `<button>` internally and are keyboard-operable BY CONSTRUCTION — the same
// guarantee jf-control / `*-button` give. `jf-filter-chip` renders `<button aria-pressed>` (its stated
// 559 controls-a11y contract), so a consumer's `@click` on the host rides the inner button's keyboard
// path (Enter/Space → button click, composed, retargeted to the host). Recognized like jf-control.
const OPERABLE_ATOM_TAGS = new Set(['jf-filter-chip']);
// Standalone interactive roles (need the tabindex triad). `scrollbar` is a real interactive ARIA role
// (the minimap-as-scrollbar thumb, tempdoc 565 §21) operated by the tabindex+keydown triad like slider.
const STANDALONE_ROLE =
  /role=["'`](button|menuitem|menuitemcheckbox|menuitemradio|switch|checkbox|radio|link|combobox|slider|spinbutton|scrollbar)["'`]/;
// Row / grid-cell roles in a keyboard-navigable container (the container owns the
// keyboard; a sortable columnheader additionally carries its own tabindex+keydown).
const ROW_ROLE = /role=["'`](option|row|listitem|treeitem|gridcell|cell|columnheader|tab)["'`]/;
// A handler that only stops propagation / prevents default is not an affordance.
const STOPPROP =
  /@(?:click|mousedown|pointerdown)=\$\{[^}]*\.(?:stopPropagation|preventDefault)\(\)[^}]*\}/;
// Selection markers that signal the listbox/grid pattern.
const SELECTION_ATTR = /\baria-(selected|current)=/;
// Decorative dismiss layer.
const BACKDROP = /class=["'`][^"'`]*\b(backdrop|scrim|overlay)\b/;
const DISMISS_HANDLER = /@(?:click|mousedown|pointerdown)=\$\{[^}]*\b(onBackdrop|dismiss|close)\b/i;
// aria-live transient (toast): announced live + carries its real action on an
// inner button; the body click-to-acknowledge is mouse convenience.
const TRANSIENT = /role=["'`](status|alert|log)["'`]/;
// A toast wrapper: its content is announced by an inner live region; the body
// click-to-acknowledge is a mouse convenience (auto-dismiss + an inner action
// button are the keyboard paths).
const TOAST = /class=["'`][^"'`]*\btoast\b/;
// Decorative mouse-convenience that duplicates a keyboard path (removed from the
// a11y tree): a listbox option's inline "×" whose keyboard equivalent is a key.
const ARIA_HIDDEN = /aria-hidden=["'`]true["'`]/;
// `<tag …@click|@mousedown|@pointerdown…>` — the opening tag (attrs up to `>`).
const HANDLER_TAG =
  /<([a-zA-Z][a-zA-Z0-9-]*)\b([^>]*?@(?:click|mousedown|pointerdown)=[^>]*?)>/gs;

const stripComments = (s) =>
  s
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

// Inside `${…}` interpolations, replace `<` and `>` with spaces (brace-balanced),
// keeping the rest of the body. A `>` inside an arrow handler (`@click=${() => …}`)
// or a comparison would otherwise terminate the `<tag …>` match early and hide the
// `role`/`aria` declared AFTER the handler. The handler body text (e.g.
// `stopPropagation`) is preserved for the body-level exemptions. Newlines + length
// are preserved, so line numbers stay accurate.
function neutralizeInterp(s) {
  let out = '';
  for (let i = 0; i < s.length; ) {
    if (s[i] === '$' && s[i + 1] === '{') {
      out += '${';
      i += 2;
      let depth = 1;
      while (i < s.length && depth > 0) {
        const c = s[i];
        if (c === '{') depth++;
        else if (c === '}') depth--;
        if (depth === 0) {
          out += '}';
          i++;
          break;
        }
        out += c === '<' || c === '>' ? ' ' : c;
        i++;
      }
    } else {
      out += s[i];
      i++;
    }
  }
  return out;
}

const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e).replace(/\\/g, '/');
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (extname(p) === '.ts' && !/\.(test|spec)\.ts$/.test(p)) {
      files.push(p);
    }
  }
})(SRC);

const offenders = [];
for (const f of files) {
  const src = neutralizeInterp(stripComments(readFileSync(f, 'utf8')));
  for (const m of src.matchAll(HANDLER_TAG)) {
    const tag = m[1].toLowerCase();
    const attrs = m[2];
    const ok =
      NATIVE_OK.has(tag) ||
      tag.endsWith('-button') ||
      tag === 'jf-control' ||
      OPERABLE_ATOM_TAGS.has(tag) ||
      (STANDALONE_ROLE.test(attrs) && /tabindex=/.test(attrs)) ||
      ROW_ROLE.test(attrs) ||
      SELECTION_ATTR.test(attrs) ||
      BACKDROP.test(attrs) ||
      DISMISS_HANDLER.test(attrs) ||
      TRANSIENT.test(attrs) ||
      TOAST.test(attrs) ||
      ARIA_HIDDEN.test(attrs) ||
      STOPPROP.test(attrs);
    if (!ok) {
      const line = src.slice(0, m.index).split('\n').length;
      offenders.push(`${f.slice(SRC.length + 1)}:${line}  <${tag} @click…>`);
    }
  }
}

// Name-PROJECTION (559 Authority V, declaration-deepening): every core status
// metric's accessible name must PROJECT from the StatusBarItem registry through
// present({kind:'metric'}) — NOT a hand-stamped inline English string. The
// declaration lives on the registry entry (`accessibleLabel`); renderCoreItem
// composes `${name}: ${liveValue}`. This is stronger than the old count check
// (which a hand-stamped aria-label also passed): it fails if a metric's name is
// authored inline instead of projected from the catalog.
const STATUS_DECK = 'modules/ui-web/src/shell-v0/components/StatusDeck.ts';
const STATUS_REGISTRY = 'modules/ui-web/src/shell-v0/commands/StatusBarRegistry.ts';
try {
  const deck = readFileSync(STATUS_DECK, 'utf8');
  const registry = readFileSync(STATUS_REGISTRY, 'utf8');
  // (a) the catalog declares the name field.
  if (!/\baccessibleLabel\b\s*\??\s*:/.test(registry)) {
    offenders.push(
      `${STATUS_REGISTRY.slice(SRC.length + 1)}  StatusBarItem must declare an \`accessibleLabel\` ` +
        `field — the metric name present({kind:'metric'}) projects (559 Authority V).`,
    );
  }
  const body = deck.slice(deck.indexOf('renderCoreItem'), deck.indexOf('renderLeftContent'));
  const cases = (body.match(/case 'core\.[a-z-]+':/g) || []).length;
  // (b) the projection seam is used.
  if (cases > 0 && !/present\(\s*\{\s*kind:\s*['"]metric['"]/.test(body)) {
    offenders.push(
      `${STATUS_DECK.slice(SRC.length + 1)}  renderCoreItem must resolve each metric name via ` +
        `present({kind:'metric', id}) — the name projects from the registry, not an inline string (559 Authority V).`,
    );
  }
  // (c) every core metric's accessible name is projected (`${name}: …`), none authored inline.
  // The name can ride on a read-only `aria-label` (the `role="img"` metrics) OR, when the metric
  // is an OPERABLE control (595 §15.3 N3 — the system pill is a `<jf-control>` that opens Health),
  // on the control's projected `label`. Both resolve `name` from present({kind:'metric'}), so both
  // satisfy the projection intent; only an INLINE aria-label (a hand-authored name) is the defect.
  const ariaTotal = (body.match(/aria-label=/g) || []).length;
  const ariaProjected = (body.match(/aria-label="\$\{name\}/g) || []).length;
  const labelProjected = (body.match(/label="\$\{name\}/g) || []).length;
  const nameProjected = ariaProjected + labelProjected;
  if (cases > 0 && nameProjected < cases) {
    offenders.push(
      `${STATUS_DECK.slice(SRC.length + 1)}  renderCoreItem has ${cases} core metric(s) but only ` +
        `${nameProjected} projected name(s) (\`\${name}: …\` on aria-label or a jf-control label) — every ` +
        `metric name must project from present({kind:'metric'}) (559 Authority V).`,
    );
  }
  if (ariaTotal > ariaProjected) {
    offenders.push(
      `${STATUS_DECK.slice(SRC.length + 1)}  renderCoreItem has ${ariaTotal - ariaProjected} ` +
        `non-projected aria-label(s) — a metric name authored inline instead of via present({kind:'metric'}) (559 Authority V).`,
    );
  }
} catch {
  offenders.push(`controls-a11y: cannot read ${STATUS_DECK}/${STATUS_REGISTRY} for the status-name check.`);
}

// Name-RESOLUTION on the control primitive (559 Authority V §11 seam): every
// `<jf-control>` must resolve an accessible name — an `operation-id` (projected
// via present), a non-empty `label`, or literal slot text. A bare nameless
// control is the bad form the typed seam forbids; the Lit ceiling (557 §5) means
// this is the grep-grade enforcement, paired with the runtime dev-assert in
// Control.render().
// HONEST LIMIT (the same ceiling the @click scan above has): `neutralizeInterp`
// erases `<`/`>` inside `${…}`, so a control nested in an interpolation
// (`${cond ? html`<jf-control…>` : nothing}`) is NOT matched here — the runtime
// dev-assert in Control.render() is the safety net for those. This scan covers
// top-level controls (the common, easiest-to-author-wrong case).
// NB: the closing tag may be `</jf-control\n  >` (Lit formats the `>` onto a new
// line), so allow whitespace before the `>` — else such controls escape the scan.
const JF_CONTROL_EL = /<jf-control\b([^>]*)>([\s\S]*?)<\/jf-control\s*>/g;
const HAS_OP_ID = /\boperation-id=/;
const HAS_NONEMPTY_LABEL = /\blabel=(?:"[^"]*\S[^"]*"|'[^']*\S[^']*'|\$\{)/;
for (const f of files) {
  if (f.endsWith('/components/Control.ts')) continue; // the primitive's own definition
  const src = neutralizeInterp(stripComments(readFileSync(f, 'utf8')));
  for (const m of src.matchAll(JF_CONTROL_EL)) {
    const attrs = m[1];
    // Slot text after dropping nested tags + neutralized ${…} blocks + whitespace.
    const slotText = m[2].replace(/<[^>]*>/g, '').replace(/\$\{[^}]*\}/g, '').trim();
    const named = HAS_OP_ID.test(attrs) || HAS_NONEMPTY_LABEL.test(attrs) || slotText.length > 0;
    if (!named) {
      const line = src.slice(0, m.index).split('\n').length;
      offenders.push(
        `${f.slice(SRC.length + 1)}:${line}  <jf-control> resolves no accessible name — give it an ` +
          `\`operation-id\`, a non-empty \`label\`, or slot text (559 Authority V §11 seam).`,
      );
    }
  }
}

// Tempdoc 596 face 1.1 — REASON ON A DISABLED CONTROL. A `<tag>` that carries BOTH a `?disabled`
// (or native `disabled`) binding AND a `title=` is the suppressed-reason defect: a browser does NOT
// render a `title` tooltip on a disabled control (the disabled element is not even focusable), so the
// authored "why" is unreachable in exactly the state it describes (596 §1.1, verified live §11.2).
// The reason must be modeled as TYPED AVAILABILITY (jf-control `.availability` → aria-disabled + a
// reachable `aria-describedby` reason + a non-silent block), never a `title` on a disabled element.
// (Static half; the discarded-availability-boolean face 1.2 is primitive-closed — routing through
// jf-control's typed availability removes the boolean to discard — per 596 §14/U3.)
const OPEN_TAG = /<([a-zA-Z][a-zA-Z0-9-]*)\b([^>]*)>/gs;
// Match a disabled binding but NOT `aria-disabled` (the CORRECT soft mechanism): require start/space
// before `disabled`, which `aria-disabled` (preceded by `-`) cannot satisfy.
const DISABLED_BIND = /(?:^|\s)(?:\?disabled|disabled)=/;
const TITLE_ATTR = /(?:^|\s)title=/;
// Per-file count of pre-existing title-on-disabled instances (596 face 1.1). A SHRINKING ratchet
// (mirrors accent-as-text / atom-fork-ratchet): NEW instances fail; the listed debt is pinned for
// migration to typed availability. `--rebalance` rewrites the baseline as instances are migrated.
const DT_BASELINE_PATH = 'scripts/ci/controls-a11y-disabled-title-baseline.v1.json';
let dtBaseline = {};
try {
  dtBaseline = JSON.parse(readFileSync(DT_BASELINE_PATH, 'utf8'));
} catch {
  /* no baseline yet → every instance is an offender */
}
const dtCounts = {};
const dtByFile = {};
for (const f of files) {
  if (f.endsWith('/components/Control.ts')) continue; // the primitive that renders the correct form
  const rel = f.slice(SRC.length + 1);
  const src = neutralizeInterp(stripComments(readFileSync(f, 'utf8')));
  for (const m of src.matchAll(OPEN_TAG)) {
    const attrs = m[2];
    if (DISABLED_BIND.test(attrs) && TITLE_ATTR.test(attrs)) {
      const line = src.slice(0, m.index).split('\n').length;
      dtCounts[rel] = (dtCounts[rel] ?? 0) + 1;
      (dtByFile[rel] ??= []).push(`${rel}:${line}  <${m[1].toLowerCase()}> disabled + title`);
    }
  }
}
if (process.argv.includes('--rebalance')) {
  writeFileSync(DT_BASELINE_PATH, JSON.stringify(dtCounts, null, 2) + '\n');
  dtBaseline = dtCounts; // evaluate this run against the freshly-written baseline (exit clean)
  console.log(`controls-a11y: rebalanced ${DT_BASELINE_PATH} (${Object.values(dtCounts).reduce((a, b) => a + b, 0)} pinned).`);
}
for (const [rel, n] of Object.entries(dtCounts)) {
  const allowed = dtBaseline[rel] ?? 0;
  if (n > allowed) {
    offenders.push(
      `${rel}  ${n} title-on-disabled instance(s) > baseline ${allowed} (596 face 1.1): a \`title\` is ` +
        `unreachable on a disabled control. Model the reason as typed availability ` +
        `(<jf-control .availability=…> → aria-disabled + aria-describedby), not a title on a disabled element. ` +
        `Sites: ${(dtByFile[rel] || []).join('; ')}`,
    );
  }
}

if (offenders.length > 0) {
  console.error(
    `✗ controls-a11y gate FAILED — ${offenders.length} finding(s). Use <jf-control> (or a native ` +
      `button / the role+tabindex+keydown triad) for affordances, and give every status metric an ` +
      `accessible name (559 Authority V):\n` +
      offenders.map((o) => '  - ' + o).join('\n'),
  );
  process.exit(1);
}
console.log(
  `✓ controls-a11y gate OK — every activation handler is on a keyboard-operable element ` +
    `(native control / <jf-control> / role+tabindex), and every status metric has an accessible name. (559 Authority V)`,
);
