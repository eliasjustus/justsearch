#!/usr/bin/env node
/**
 * Durable-state compatibility gate.
 *
 * StoreCatalog remains the encryption/recoverability authority for its six stores. The governed
 * durableStores register is broader: it records every Shell, Head, Worker, external, derived, and
 * ephemeral state owner that participates in an in-place upgrade.
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { scanDurableStores } from '../governance/lib/durable-store-scan.mjs';

const REGISTER = 'governance/store-recoverability.v1.json';
const OWNERS = new Set(['SHELL', 'HEAD', 'WORKER', 'EXTERNAL']);
const RECOVERABILITY = new Set(['AUTHORED', 'DERIVED', 'MIXED', 'EPHEMERAL']);
const STATUSES = new Set(['READY', 'HARDENING_REQUIRED']);

/** Extract `NAME("dir", StoreRecoverability.CLASS, ...)` entries from StoreCatalog.java. */
export function extractCatalogEntries(javaSrc) {
  const entries = [];
  const re =
    /\b([A-Z][A-Z0-9_]*)\s*\(\s*"([^"]+)"\s*,\s*StoreRecoverability\.(AUTHORED|DERIVED)/g;
  let match;
  while ((match = re.exec(javaSrc)) !== null) {
    entries.push({ constant: match[1], dirName: match[2], recoverability: match[3] });
  }
  return entries;
}

/** Find bare-literal cipher selections instead of StoreCatalog-derived selections. */
export function findHardcodedCipherCalls(javaSrc) {
  const hits = [];
  const re = /storeCipher\(\s*(?:[\w.]*\.)?StoreRecoverability\.(AUTHORED|DERIVED)/g;
  let match;
  while ((match = re.exec(javaSrc)) !== null) hits.push(match[1]);
  return hits;
}

/** Preserve the original StoreCatalog-to-register mirror invariant. */
export function checkParity(catalogEntries, registerStores) {
  const failures = [];
  const catalog = new Map(catalogEntries.map((entry) => [entry.dirName, entry.recoverability]));
  const register = new Map(registerStores.map((entry) => [entry.dirName, entry.recoverability]));

  for (const [dirName, recoverability] of catalog) {
    if (!register.has(dirName)) {
      failures.push(
        `parity: StoreCatalog declares \`${dirName}\` (${recoverability}) but ${REGISTER} has no mirror row.`,
      );
    } else if (register.get(dirName) !== recoverability) {
      failures.push(
        `parity: \`${dirName}\` is ${recoverability} in StoreCatalog but ${register.get(dirName)} in ${REGISTER}.`,
      );
    }
  }
  for (const [dirName, recoverability] of register) {
    if (!catalog.has(dirName)) {
      failures.push(
        `parity: ${REGISTER} mirrors \`${dirName}\` (${recoverability}) but StoreCatalog has no entry.`,
      );
    }
  }
  return failures;
}

/**
 * Validate the broad durable-state register. The known-gap list is an explicit ratchet: only rows
 * already named there may remain HARDENING_REQUIRED while tempdoc 617 converts them.
 */
export function checkDurableStoreRegister({
  root,
  durableStores,
  knownCompatibilityGaps,
  catalogEntries,
  discoveredStores,
  pathExists = existsSync,
}) {
  const failures = [];
  const rows = Array.isArray(durableStores) ? durableStores : [];
  const gaps = new Set(Array.isArray(knownCompatibilityGaps) ? knownCompatibilityGaps : []);
  const ids = new Set();
  const coveredImplementations = new Set();
  const catalogRows = new Map();

  for (const row of rows) {
    const label = row?.id ? `durableStores.${row.id}` : 'durableStores.<missing-id>';
    if (!row?.id) {
      failures.push(`${label}: id is required.`);
      continue;
    }
    if (ids.has(row.id)) failures.push(`${label}: duplicate id.`);
    ids.add(row.id);

    if (!OWNERS.has(row.owner)) failures.push(`${label}: invalid owner \`${row.owner}\`.`);
    if (!RECOVERABILITY.has(row.recoverability)) {
      failures.push(`${label}: invalid recoverability \`${row.recoverability}\`.`);
    }
    if (!STATUSES.has(row.status)) failures.push(`${label}: invalid status \`${row.status}\`.`);
    for (const field of ['root', 'path', 'format', 'atomicity', 'corruptionPolicy', 'reconciliation']) {
      if (typeof row[field] !== 'string' || row[field].trim() === '') {
        failures.push(`${label}: ${field} is required.`);
      }
    }

    const sources = Array.isArray(row.implementationSources) ? row.implementationSources : [];
    if (sources.length === 0) failures.push(`${label}: implementationSources must not be empty.`);
    for (const source of sources) {
      coveredImplementations.add(normalize(source));
      if (!pathExists(resolve(root, source))) failures.push(`${label}: source does not exist: ${source}.`);
    }
    for (const evidence of [...(row.tests ?? []), ...(row.fixtures ?? [])]) {
      if (!pathExists(resolve(root, evidence))) {
        failures.push(`${label}: evidence does not exist: ${evidence}.`);
      }
    }

    if (row.catalogDirName) {
      if (catalogRows.has(row.catalogDirName)) {
        failures.push(`${label}: duplicate catalogDirName \`${row.catalogDirName}\`.`);
      }
      catalogRows.set(row.catalogDirName, row);
    }

    if (row.status === 'READY') {
      if (
        (row.recoverability === 'AUTHORED' || row.recoverability === 'MIXED') &&
        row.corruptionPolicy === 'SILENT_EMPTY'
      ) {
        failures.push(`${label}: authored/mixed state cannot use SILENT_EMPTY recovery.`);
      }
      if (
        row.writeMode === 'FULL_REWRITE' &&
        !['ATOMIC_REPLACE', 'TRANSACTIONAL'].includes(row.atomicity)
      ) {
        failures.push(`${label}: a READY full rewrite must be atomic or transactional.`);
      }
      if (Number(row.currentVersion) > 0) {
        if (!row.versionAuthority) failures.push(`${label}: versionAuthority is required.`);
        if (!row.futureVersionRefusalTest) {
          failures.push(`${label}: futureVersionRefusalTest is required.`);
        } else if (!pathExists(resolve(root, row.futureVersionRefusalTest))) {
          failures.push(
            `${label}: futureVersionRefusalTest does not exist: ${row.futureVersionRefusalTest}.`,
          );
        }
      }
    }
  }

  for (const id of gaps) {
    const row = rows.find((candidate) => candidate.id === id);
    if (!row) failures.push(`knownCompatibilityGaps names missing durable store \`${id}\`.`);
    else if (row.status !== 'HARDENING_REQUIRED') {
      failures.push(`knownCompatibilityGaps names READY durable store \`${id}\`.`);
    }
  }
  for (const row of rows) {
    if (row.status === 'HARDENING_REQUIRED' && !gaps.has(row.id)) {
      failures.push(
        `durableStores.${row.id}: HARDENING_REQUIRED is not ratcheted in knownCompatibilityGaps.`,
      );
    }
  }

  for (const source of discoveredStores ?? []) {
    if (!coveredImplementations.has(normalize(source))) {
      failures.push(`coverage: discovered durable store is unregistered: ${source}.`);
    }
  }

  for (const catalogEntry of catalogEntries ?? []) {
    const row = catalogRows.get(catalogEntry.dirName);
    if (!row) {
      failures.push(
        `coverage: StoreCatalog.${catalogEntry.constant} has no durableStores row with catalogDirName \`${catalogEntry.dirName}\`.`,
      );
    } else if (row.recoverability !== catalogEntry.recoverability) {
      failures.push(
        `coverage: durableStores.${row.id} recoverability disagrees with StoreCatalog.${catalogEntry.constant}.`,
      );
    }
  }

  return failures;
}

function main() {
  const root = process.cwd();
  const register = JSON.parse(readFileSync(resolve(root, REGISTER), 'utf8'));
  const catalogEntries = extractCatalogEntries(
    readFileSync(resolve(root, register.catalog.file), 'utf8'),
  );
  if (catalogEntries.length === 0) {
    console.error('store-recoverability gate FAILED: no StoreCatalog entries were parsed.');
    process.exit(1);
  }

  const failures = [
    ...checkParity(catalogEntries, register.stores ?? []),
    ...checkDurableStoreRegister({
      root,
      durableStores: register.durableStores,
      knownCompatibilityGaps: register.knownCompatibilityGaps,
      catalogEntries,
      discoveredStores: scanDurableStores(root),
    }),
  ];

  for (const site of register.constructionSites ?? []) {
    const hits = findHardcodedCipherCalls(readFileSync(resolve(root, site), 'utf8'));
    if (hits.length > 0) {
      failures.push(
        `no-hardcode: ${site} selects a cipher with a bare StoreRecoverability.${hits[0]} literal.`,
      );
    }
  }

  if (failures.length > 0) {
    console.error(
      'store-recoverability gate FAILED:\n' + failures.map((failure) => `  - ${failure}`).join('\n'),
    );
    process.exit(1);
  }

  console.log(
    `store-recoverability gate OK - ${catalogEntries.length} catalog stores and ` +
      `${register.durableStores.length} durable state authorities are registered.`,
  );
}

function normalize(path) {
  return String(path ?? '').replace(/\\/g, '/');
}

if (
  process.argv[1] &&
  process.argv[1].replace(/\\/g, '/').endsWith('check-store-recoverability.mjs')
) {
  main();
}
