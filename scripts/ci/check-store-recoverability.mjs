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

import { scanPersistenceWriteSites } from '../governance/lib/persistence-write-scan.mjs';

const REGISTER = 'governance/store-recoverability.v1.json';
const OWNERS = new Set(['SHELL', 'HEAD', 'WORKER', 'EXTERNAL']);
const RECOVERABILITY = new Set(['AUTHORED', 'DERIVED', 'MIXED', 'EPHEMERAL']);
const STATUSES = new Set(['READY', 'HARDENING_REQUIRED']);
const UPGRADE_HANDLING = new Set([
  'READ_IN_PLACE',
  'REBUILD',
  'RESET',
  'PRESERVE_EXTERNAL',
]);
/**
 * How a row's declared `ownedPaths` are held to the code that writes them. LITERAL rows are checked
 * segment-by-segment against the string literals in their implementationSources; COMPOSED rows are a
 * STATED exclusion — the path is assembled at runtime, and the row must say why. There is no third
 * value, so "nobody answered the question" is unrepresentable.
 */
const PATH_VERIFICATION = new Set(['LITERAL', 'COMPOSED']);
/**
 * Whether a row's bytes are sealed at rest, and if not, why not. Every row answers; the absence of an
 * answer is a build failure. UNSEALED_GAP is the honest label for "no structural reason, just not done";
 * UNSEALED_DERIVED_OS_DISK_ENCRYPTION is the deliberate StoreCatalog Framing.OPAQUE position — a DERIVED,
 * rebuildable store that holds user-derived bytes and is covered by OS disk encryption only; and
 * NOT_APPLICABLE claims there is nothing here worth sealing. All three are claims about content, so all
 * three carry an encryptionNote saying what the file actually contains (tempdoc 879: NOT_APPLICABLE was
 * the one value nobody had to justify, and it was standing in for both "nothing sensitive" and "the
 * user's own document text").
 */
const ENCRYPTION = new Set([
  'SEALED_BY_STORE_CIPHER',
  'UNSEALED_KEY_ROOT',
  'UNSEALED_EXTERNAL_AUTHORITY',
  'UNSEALED_NO_JVM_CIPHER',
  'UNSEALED_DERIVED_OS_DISK_ENCRYPTION',
  'UNSEALED_GAP',
  'NOT_APPLICABLE',
]);
/** The dispositions that are claims about what the bytes hold, and so must say what that is. */
const ENCRYPTION_NOTE_REQUIRED = new Map([
  ['UNSEALED_GAP', 'saying what the plaintext file contains'],
  [
    'UNSEALED_DERIVED_OS_DISK_ENCRYPTION',
    'naming the user-derived content it holds in the clear and why the store is rebuildable',
  ],
  ['NOT_APPLICABLE', 'saying what the file holds and why none of it is worth sealing'],
]);

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
 * Every path component that appears inside a double-quoted string literal of a source file: the whole
 * literal, plus each of its slash-separated parts, so `"ui/settings.json"` answers for `ui` and for
 * `settings.json` while `"ui"` answers only for `ui`. Component equality (not substring) is the match
 * rule, so a literal containing `build` can never stand in for the declared segment `ui`.
 *
 * Callers pass ONE source's text at a time (checkPathAgreement requires a single file to answer for a
 * whole declared path); the array form is kept for the union view a caller may still want.
 */
export function literalPathComponents(sources) {
  const components = new Set();
  const re = /"((?:[^"\\\n]|\\.)*)"/g;
  for (const src of sources) {
    let match;
    while ((match = re.exec(src)) !== null) {
      const value = match[1];
      components.add(value);
      for (const part of value.split(/[\\/]/)) if (part) components.add(part);
    }
  }
  return components;
}

/** The declared segments of one owned path that a literal check can speak about (globs cannot). */
export function checkablePathSegments(ownedPath) {
  return String(ownedPath ?? '')
    .split('/')
    .filter((segment) => segment !== '' && !segment.includes('*'));
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
  discoveredWriteSites,
  nonDurableWriteSites = [],
  pathExists = existsSync,
  readSource = (absolutePath) => readFileSync(absolutePath, 'utf8'),
}) {
  const failures = [];
  const authoredCatalogDirs = new Set(
    (catalogEntries ?? [])
      .filter((entry) => entry.recoverability === 'AUTHORED')
      .map((entry) => entry.dirName),
  );
  const rows = Array.isArray(durableStores) ? durableStores : [];
  const gaps = new Set(Array.isArray(knownCompatibilityGaps) ? knownCompatibilityGaps : []);
  const ids = new Set();
  const coveredImplementations = new Set();
  const classifiedNonDurable = new Set((nonDurableWriteSites ?? []).map(normalize));
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
    if (!UPGRADE_HANDLING.has(row.upgradeHandling)) {
      failures.push(`${label}: invalid upgradeHandling \`${row.upgradeHandling}\`.`);
    }
    if (!Array.isArray(row.ownedPaths) || row.ownedPaths.length === 0) {
      failures.push(`${label}: ownedPaths must name at least one exact path or glob.`);
    } else if (
      row.ownedPaths.some((path) => typeof path !== 'string' || path.trim() === '')
    ) {
      failures.push(`${label}: ownedPaths entries must be non-blank strings.`);
    }
    for (const field of ['root', 'format', 'atomicity', 'corruptionPolicy', 'reconciliation']) {
      if (typeof row[field] !== 'string' || row[field].trim() === '') {
        failures.push(`${label}: ${field} is required.`);
      }
    }

    const sources = Array.isArray(row.implementationSources) ? row.implementationSources : [];
    if (sources.length === 0) failures.push(`${label}: implementationSources must not be empty.`);
    const readableSources = [];
    for (const source of sources) {
      coveredImplementations.add(normalize(source));
      if (!pathExists(resolve(root, source))) failures.push(`${label}: source does not exist: ${source}.`);
      else readableSources.push(source);
    }

    failures.push(...checkPathAgreement({ root, row, label, readableSources, readSource }));
    failures.push(...checkEncryptionDisposition({ row, label, authoredCatalogDirs }));
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
        row.upgradeHandling === 'READ_IN_PLACE' &&
        !['ATOMIC_REPLACE', 'TRANSACTIONAL'].includes(row.atomicity)
      ) {
        failures.push(`${label}: a READY full rewrite must be atomic or transactional.`);
      }
      if (row.upgradeHandling === 'READ_IN_PLACE' && Number(row.currentVersion) <= 0) {
        failures.push(`${label}: READ_IN_PLACE state must have a positive currentVersion.`);
      }
      if (row.upgradeHandling === 'READ_IN_PLACE') {
        if (!row.versionAuthority) failures.push(`${label}: versionAuthority is required.`);
        if (!row.futureVersionRefusalTest) {
          failures.push(`${label}: futureVersionRefusalTest is required.`);
        } else if (!pathExists(resolve(root, row.futureVersionRefusalTest))) {
          failures.push(
            `${label}: futureVersionRefusalTest does not exist: ${row.futureVersionRefusalTest}.`,
          );
        }
      }
      if (
        ['REBUILD', 'RESET', 'PRESERVE_EXTERNAL'].includes(row.upgradeHandling)
        && (row.tests ?? []).length === 0
      ) {
        failures.push(`${label}: ${row.upgradeHandling} requires a recovery/preservation test.`);
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

  for (const source of discoveredWriteSites ?? []) {
    const normalized = normalize(source);
    if (!coveredImplementations.has(normalized) && !classifiedNonDurable.has(normalized)) {
      failures.push(`coverage: persistence write site is unclassified: ${source}.`);
    }
  }
  for (const source of classifiedNonDurable) {
    if (!pathExists(resolve(root, source))) {
      failures.push(`coverage: classified non-durable write site does not exist: ${source}.`);
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

/**
 * Hold a row's declared paths to the code that writes them. Without this the register could name any
 * path at all and stay green — which is how four rows drifted from their real on-disk locations
 * (tempdoc 879). A row opts out only by SAYING so: pathVerification COMPOSED plus a note.
 *
 * ONE source file must answer for a whole declared path. Unioning the literals of every listed source
 * made LITERAL satisfiable by adding an unrelated file that happens to contain the missing word — the
 * row goes green without any file actually writing the path. Requiring a single file to hold every
 * segment means the match is evidence of a writer, not of a well-stocked source list.
 */
export function checkPathAgreement({ root, row, label, readableSources, readSource }) {
  const failures = [];
  if (!PATH_VERIFICATION.has(row.pathVerification)) {
    failures.push(
      `${label}: pathVerification is required and must be one of ${[...PATH_VERIFICATION].join(', ')}.`,
    );
    return failures;
  }
  if (row.pathVerification === 'COMPOSED') {
    if (typeof row.pathVerificationNote !== 'string' || row.pathVerificationNote.trim() === '') {
      failures.push(
        `${label}: pathVerification COMPOSED requires a pathVerificationNote saying which segments are assembled at runtime and by what.`,
      );
    }
    return failures;
  }

  const perSource = [];
  for (const source of readableSources) {
    try {
      perSource.push({ source, components: literalPathComponents([readSource(resolve(root, source))]) });
    } catch (error) {
      failures.push(`${label}: cannot read implementation source ${source}: ${error.message}.`);
    }
  }
  for (const ownedPath of row.ownedPaths ?? []) {
    const segments = checkablePathSegments(ownedPath);
    if (segments.length === 0) continue;
    if (perSource.some(({ components }) => segments.every((segment) => components.has(segment)))) {
      continue;
    }
    const best = perSource
      .map(({ source, components }) => ({
        source,
        missing: segments.filter((segment) => !components.has(segment)),
      }))
      .sort((a, b) => a.missing.length - b.missing.length)[0];
    const detail = best
      ? `closest source ${best.source} is missing ${best.missing.map((s) => `\`${s}\``).join(', ')}`
      : 'no readable implementation source';
    failures.push(
      `${label}: ownedPaths declares \`${ownedPath}\` but no SINGLE implementation source contains ` +
        `every one of its string literals ${segments.map((s) => `\`${s}\``).join(', ')} ` +
        `(searched: ${readableSources.join(', ') || 'none'}; ${detail}). ` +
        `Either the declared path is wrong, the writing source is missing from implementationSources, ` +
        `or the path is assembled at runtime — say so with pathVerification COMPOSED + pathVerificationNote. ` +
        `Adding a second file that merely mentions the missing segment does not make the path literal.`,
    );
  }
  return failures;
}

/**
 * Every row states whether its bytes are sealed at rest and, when they are not, which reason applies.
 * A store the StoreCatalog calls AUTHORED must be sealed; every disposition that is a claim about the
 * file's content must name that content. This records the disposition — it does not change behavior.
 */
export function checkEncryptionDisposition({ row, label, authoredCatalogDirs }) {
  const failures = [];
  if (!ENCRYPTION.has(row.encryption)) {
    failures.push(
      `${label}: encryption is required and must be one of ${[...ENCRYPTION].join(', ')}.`,
    );
    return failures;
  }
  const noteObligation = ENCRYPTION_NOTE_REQUIRED.get(row.encryption);
  if (
    noteObligation
    && (typeof row.encryptionNote !== 'string' || row.encryptionNote.trim() === '')
  ) {
    failures.push(
      `${label}: encryption ${row.encryption} requires an encryptionNote ${noteObligation}.`,
    );
  }

  const paths = (row.ownedPaths ?? []).map(normalize);
  const insideAuthoredCatalogDir =
    paths.length > 0
    && paths.every((path) => [...authoredCatalogDirs].some((dir) => path.startsWith(`${dir}/`)));
  const isCatalogAuthored = Boolean(row.catalogDirName) && authoredCatalogDirs.has(row.catalogDirName);
  if ((isCatalogAuthored || insideAuthoredCatalogDir) && row.encryption !== 'SEALED_BY_STORE_CIPHER') {
    failures.push(
      `${label}: this row is an AUTHORED StoreCatalog store (or lives entirely inside one) and must ` +
        `declare encryption SEALED_BY_STORE_CIPHER, not \`${row.encryption}\`.`,
    );
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
      discoveredWriteSites: scanPersistenceWriteSites(root),
      nonDurableWriteSites: register.nonDurableWriteSites,
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
