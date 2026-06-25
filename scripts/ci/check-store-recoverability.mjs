#!/usr/bin/env node
/**
 * store-recoverability gate — tempdoc 629 #5.
 *
 * The DERIVED/AUTHORED store classification is ONE authority: the Java `StoreCatalog` enum. This gate
 * promotes that to a governed register (`governance/store-recoverability.v1.json`) now that three
 * obligations project from it (encryption cipher-selection, encrypted backup/export, export-first-
 * uninstall). It enforces two invariants so a new store cannot silently land unclassified:
 *
 *  - PARITY: every `StoreCatalog` entry ↔ a register `stores` row (same dirName + recoverability), both
 *    directions. The Java enum is the source; the register is the reviewable mirror; they cannot drift.
 *  - NO-HARDCODE: every `storeCipher(...)` call in a registered construction site reads the catalog
 *    (`StoreCatalog.X.recoverability()`), never a bare `StoreRecoverability.AUTHORED|DERIVED` literal —
 *    so a new store cannot get its at-rest cipher without first having a catalog entry. THIS is what makes
 *    "a new unclassified store fails the build" true (a bare-literal cipher is the unclassified path).
 *
 * Honest limit (as with the sibling check-*.mjs gates): the construction-site list is curated in the
 * register, not auto-discovered — a wrong future omission is reviewable, far better than a brittle
 * scan of every `dataDir.resolve(...)` (most of which are non-content paths: encryption, runtime, …).
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/store-recoverability.v1.json';

/** Extract `NAME("dir", StoreRecoverability.CLASS, ...)` entries from StoreCatalog.java. */
export function extractCatalogEntries(javaSrc) {
  const entries = [];
  const re = /\b([A-Z][A-Z0-9_]*)\s*\(\s*"([^"]+)"\s*,\s*StoreRecoverability\.(AUTHORED|DERIVED)/g;
  let m;
  while ((m = re.exec(javaSrc)) !== null) {
    entries.push({ constant: m[1], dirName: m[2], recoverability: m[3] });
  }
  return entries;
}

/** Find bare-literal cipher selections: `storeCipher(... StoreRecoverability.AUTHORED|DERIVED ...)`. */
export function findHardcodedCipherCalls(javaSrc) {
  const hits = [];
  const re = /storeCipher\(\s*(?:[\w.]*\.)?StoreRecoverability\.(AUTHORED|DERIVED)/g;
  let m;
  while ((m = re.exec(javaSrc)) !== null) hits.push(m[1]);
  return hits;
}

/** Pure parity check. Returns failure strings (empty = pass). */
export function checkParity(catalogEntries, registerStores) {
  const failures = [];
  const cat = new Map(catalogEntries.map((e) => [e.dirName, e.recoverability]));
  const reg = new Map(registerStores.map((s) => [s.dirName, s.recoverability]));

  for (const [dir, cls] of cat) {
    if (!reg.has(dir)) {
      failures.push(
        `parity: StoreCatalog declares store \`${dir}\` (${cls}) but ${REGISTER} has no row — add it.`,
      );
    } else if (reg.get(dir) !== cls) {
      failures.push(
        `parity: \`${dir}\` is ${cls} in StoreCatalog but ${reg.get(dir)} in ${REGISTER} — they drifted.`,
      );
    }
  }
  for (const [dir, cls] of reg) {
    if (!cat.has(dir)) {
      failures.push(
        `parity: ${REGISTER} declares \`${dir}\` (${cls}) but StoreCatalog has no such entry — ` +
          `remove the row or add the catalog entry.`,
      );
    }
  }
  return failures;
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const catalogEntries = extractCatalogEntries(readFileSync(reg.catalog.file, 'utf8'));
  if (catalogEntries.length === 0) {
    console.error(`✗ store-recoverability gate FAILED: no StoreCatalog entries parsed — the seam moved.`);
    process.exit(1);
  }

  const failures = checkParity(catalogEntries, reg.stores ?? []);

  for (const site of reg.constructionSites ?? []) {
    const hits = findHardcodedCipherCalls(readFileSync(site, 'utf8'));
    if (hits.length > 0) {
      failures.push(
        `no-hardcode: ${site} selects a cipher with a bare StoreRecoverability.${hits[0]} literal ` +
          `(×${hits.length}) — read the catalog instead: storeCipher(StoreCatalog.<STORE>.recoverability()). ` +
          `A bare literal is how an unclassified store gets a cipher without a catalog entry.`,
      );
    }
  }

  if (failures.length > 0) {
    console.error(
      '✗ store-recoverability gate FAILED (tempdoc 629 #5):\n' +
        failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ store-recoverability gate OK — StoreCatalog ↔ ${REGISTER} parity holds ` +
      `(${catalogEntries.length} stores), and all cipher selection reads the catalog (no hardcoded class).`,
  );
}

if (
  process.argv[1] &&
  process.argv[1].replace(/\\/g, '/').endsWith('check-store-recoverability.mjs')
) {
  main();
}
