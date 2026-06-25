#!/usr/bin/env node
/**
 * surface-composition gate — tempdoc 571 §11 / 578 (the host/member composition one-home integrity).
 *
 * A host surface declares the member surfaces it presents inside itself (rendered as tabs by
 * `<jf-surface-tabs>`) via `.withMembers(List.of(new SurfaceRef("core.x"), …))` in
 * CoreSurfaceCatalog.java. Membership is the SINGLE home-authority: a member's home is its host, so it
 * is excluded from the rail (Shell) and its deep-link resolves to the host (catalogResolver). This gate
 * forecloses the contradictions that membership-as-an-improvisation used to permit (the original bug:
 * Logs both embedded in Health AND a stray rail icon):
 *
 *   1. a member that is ALSO a RAIL surface           → fail (two homes)
 *   2. a member hosted by TWO hosts                   → fail (ambiguous home)
 *   3. a member ref that resolves to no surface       → fail (dangling)
 *   4. a surface that hosts itself                    → fail
 *
 * This is referential integrity over a DECLARATION, not a derivation — it computes nothing from
 * consumed authority (altitude is the surface-altitude gate's concern; `members` is deliberately NOT an
 * input to SurfaceAltitude.derive, 578 §10 U1). Lighter scripts/ci tier; wired in the CLAUDE.md
 * pre-merge list. With zero hosts declared it passes trivially (ready for the first host).
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/surface-composition.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
const catalogPath = reg.scan.surfaceCatalog;
const src = readFileSync(catalogPath, 'utf8');

// Tempdoc 578 Option A — composition spans declaration sources. A Java host may present a member that
// is declared in the FE CorePlugin contributions (e.g. core.memory-surface), not the Java catalog.
// Collect the CorePlugin-contributed surface ids so the dangling-member check resolves against the
// MERGED surface set (the FE member→host resolution + rail-exclusion already run over the merged
// listSurfaces()). The one-home RAIL check below stays Java-only — a CorePlugin-only member carries no
// Java placement, and the runtime membership-exclusion (Shell) removes it from the rail regardless.
const corePluginPath = reg.scan.corePlugin;
const corePluginIds = new Set();
if (corePluginPath) {
  const cp = readFileSync(corePluginPath, 'utf8');
  for (const m of cp.matchAll(/\bid:\s*['"]([^'"]+)['"]/g)) corePluginIds.add(m[1]);
}

// Constant map: NAME = new SurfaceRef("core.x").
const refMap = new Map();
for (const m of src.matchAll(/(\w+)\s*=\s*new\s+SurfaceRef\(\s*"([^"]+)"\s*\)/g)) {
  refMap.set(m[1], m[2]);
}
// Parse each `new Surface(...)` segment for { id, placement, members }. `.withMembers(List.of(...))`
// is chained after the constructor closes, so it lives in the same split-segment.
const segments = src.split(/new\s+Surface\(/).slice(1);
const surfaces = [];
for (const seg of segments) {
  const idConst = seg.match(/^\s*(\w+)/)?.[1];
  if (!idConst) continue;
  const id = refMap.get(idConst) ?? idConst;
  const placement = seg.match(/Placement\.(\w+)/)?.[1] ?? null;
  let members = [];
  // Extract the balanced `.withMembers(List.of( … ))` region, then pull member refs from it. Balanced
  // extraction (not a non-greedy regex) is required because an inline `new SurfaceRef("…")` member
  // contains its own parens, which a `…?\)\s*\)` match would stop short on.
  const wmIdx = seg.indexOf('.withMembers(');
  if (wmIdx >= 0) {
    let depth = 0;
    let region = '';
    for (let i = seg.indexOf('(', wmIdx); i < seg.length; i++) {
      const ch = seg[i];
      if (ch === '(') depth++;
      else if (ch === ')') {
        depth--;
        if (depth === 0) break;
      }
      region += ch;
    }
    // Inline `new SurfaceRef("core.x")` members → the string; bare `FOO_SURFACE_ID` constants → refMap.
    const seen = new Set();
    for (const m of region.matchAll(/new\s+SurfaceRef\(\s*"([^"]+)"\s*\)/g)) seen.add(m[1]);
    for (const m of region.matchAll(/\b([A-Z][A-Z0-9_]*_SURFACE_ID)\b/g)) {
      const v = refMap.get(m[1]);
      if (v) seen.add(v);
    }
    members = [...seen];
  }
  surfaces.push({ id, placement, members });
}

// Merged id set (Java catalog ∪ CorePlugin contributions) — the dangling-member resolution authority.
const allIds = new Set([...surfaces.map((s) => s.id), ...corePluginIds]);
// RAIL set is Java-only (the only placement this static gate authoritatively knows); a CorePlugin-only
// member is governed by runtime membership-exclusion, not a static RAIL assertion.
const railIds = new Set(surfaces.filter((s) => s.placement === 'RAIL').map((s) => s.id));
const hostByMember = new Map(); // memberId -> [hostId,…]
for (const s of surfaces) {
  for (const m of s.members) {
    hostByMember.set(m, [...(hostByMember.get(m) ?? []), s.id]);
  }
}

const failures = [];
for (const s of surfaces) {
  for (const m of s.members) {
    if (m === s.id) {
      failures.push(`${s.id} declares ITSELF as a member — a surface cannot host itself.`);
    }
    if (!allIds.has(m)) {
      failures.push(`${s.id} hosts member '${m}', which resolves to no declared surface (dangling).`);
    }
    if (railIds.has(m)) {
      failures.push(
        `member '${m}' (hosted by ${s.id}) is ALSO a RAIL surface — a member's home is its host. ` +
          `Remove its Placement.RAIL (it stays deep-link routable) so it has exactly one home.`,
      );
    }
  }
}
for (const [m, hosts] of hostByMember) {
  if (hosts.length > 1) {
    failures.push(`member '${m}' is hosted by ${hosts.length} hosts (${hosts.join(', ')}) — one home only.`);
  }
}

const hostCount = surfaces.filter((s) => s.members.length > 0).length;
const memberCount = hostByMember.size;

if (failures.length > 0) {
  console.error(
    '✗ surface-composition gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'),
  );
  process.exit(1);
}
console.log(
  `✓ surface-composition gate OK — ${hostCount} host(s), ${memberCount} member(s); every member has ` +
    `exactly one home (its host, off the rail, deep-link-routable). Referential-integrity over the ` +
    `declared host/member relationship (571 §11 / 578).`,
);
