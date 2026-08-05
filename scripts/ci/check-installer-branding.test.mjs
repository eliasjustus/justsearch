#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';

import { checkBrandingSource, checkFullPageBranding } from './check-installer-branding.mjs';

const failures = [];
let passed = 0;

function test(label, assertion) {
  try {
    assertion();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

// -------------------- branding source (bundle.copyright) --------------------

const CLEAN_CONF = JSON.stringify({
  version: '0.2.0',
  bundle: { copyright: 'JustSearch 0.2.0 - Copyright (c) JustSearch' },
});

test('the shipped tauri.conf.json branding shape passes', () => {
  assert.deepEqual(checkBrandingSource(CLEAN_CONF), []);
});

test('a missing bundle.copyright fails', () => {
  const conf = JSON.stringify({ version: '0.2.0', bundle: {} });
  assert.equal(checkBrandingSource(conf).length, 1);
});

test('an empty bundle.copyright fails', () => {
  const conf = JSON.stringify({ version: '0.2.0', bundle: { copyright: '   ' } });
  assert.equal(checkBrandingSource(conf).length, 1);
});

test('a copyright naming a different version than the bundle fails', () => {
  const conf = JSON.stringify({
    version: '0.2.0',
    bundle: { copyright: 'JustSearch 0.1.9 - Copyright (c) JustSearch' },
  });
  assert.equal(checkBrandingSource(conf).length, 1);
});

test('unparseable tauri.conf.json is a failure, not a silent pass', () => {
  assert.equal(checkBrandingSource('{not json').length, 1);
});

// -------------------- MUI full pages (Welcome, Finish) --------------------

const CLEAN_NSH = `
!define MUI_WELCOMEPAGE_TEXT "$(MUI_TEXT_WELCOME_INFO_TEXT)$\\r$\\n$\\r$\\n\${COPYRIGHT}"
!define MUI_FINISHPAGE_TEXT_LARGE
!define MUI_FINISHPAGE_TEXT "$(MUI_TEXT_FINISH_INFO_TEXT)$\\r$\\n$\\r$\\n\${COPYRIGHT}"

!macro NSIS_HOOK_PREINSTALL
!macroend
`;

test('the shipped installer-hooks.nsh full-page shape passes', () => {
  assert.deepEqual(checkFullPageBranding(CLEAN_NSH), []);
});

test('dropping the Welcome page text fails', () => {
  const nsh = CLEAN_NSH.split('\n')
    .filter((line) => !line.includes('MUI_WELCOMEPAGE_TEXT'))
    .join('\n');
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('dropping the Finish page text fails', () => {
  const nsh = CLEAN_NSH.split('\n')
    .filter((line) => !/MUI_FINISHPAGE_TEXT\s/.test(line))
    .join('\n');
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('a page text that does not reference ${COPYRIGHT} fails', () => {
  const nsh = CLEAN_NSH.replace(
    '$(MUI_TEXT_WELCOME_INFO_TEXT)$\\r$\\n$\\r$\\n${COPYRIGHT}',
    'Welcome to JustSearch',
  );
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('a hard-coded version string instead of ${COPYRIGHT} fails (it would drift)', () => {
  const nsh = CLEAN_NSH.replace(
    '$(MUI_TEXT_FINISH_INFO_TEXT)$\\r$\\n$\\r$\\n${COPYRIGHT}',
    'JustSearch 0.2.0 installed.',
  );
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('overriding the finish text without MUI_FINISHPAGE_TEXT_LARGE fails (clipped line)', () => {
  const nsh = CLEAN_NSH.split('\n')
    .filter((line) => !line.includes('MUI_FINISHPAGE_TEXT_LARGE'))
    .join('\n');
  assert.equal(checkFullPageBranding(nsh).length, 1);
});

test('commented-out defines do not count as present', () => {
  const nsh = CLEAN_NSH.split('\n')
    .map((line) => (line.startsWith('!define MUI_') ? `; ${line}` : line))
    .join('\n');
  // Welcome missing + Finish missing = 2 (the TEXT_LARGE rule only fires when TEXT is present).
  assert.equal(checkFullPageBranding(nsh).length, 2);
});

if (failures.length > 0) {
  console.error(`check-installer-branding.test FAILED (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

console.log(`check-installer-branding.test OK - ${passed} assertions passed.`);
