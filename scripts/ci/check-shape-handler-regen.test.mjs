import assert from 'node:assert/strict';

import { firstSchemaDifference } from './check-shape-handler-regen.mjs';
import { BUNDLED_SHAPES } from '../codegen/gen-shape-handlers.mjs';

let passed = 0;
const failures = [];

function test(label, assertion) {
  try {
    assertion();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

// The shape of a real `eventSchema` entry, abbreviated: an OBJECT, which is the whole
// point — the live oracle used to compare entries with `===`, so two byte-identical
// schemas arriving from two JSON parses never matched and `--live` could not pass.
function entry(name, fieldName, type = 'STRING') {
  return {
    name,
    fields: [
      { name: fieldName, type, optional: false, enumValues: [], elementType: null, objectType: null },
    ],
  };
}

test('structurally equal but not reference-equal schemas compare as equal', () => {
  const live = [entry('session_started', 'sessionId'), entry('chunk', 'text')];
  const fixture = [entry('session_started', 'sessionId'), entry('chunk', 'text')];
  // The defect made visible: the two arrays hold no object in common.
  assert.equal(
    live.some((e, i) => e === fixture[i]),
    false,
  );
  assert.equal(firstSchemaDifference(live, fixture), null);
});

test('two parses of the same JSON compare as equal', () => {
  const json = JSON.stringify([entry('chunk', 'text'), entry('done', 'reason')]);
  assert.equal(firstSchemaDifference(JSON.parse(json), JSON.parse(json)), null);
});

test('a differing field names the index and carries both values', () => {
  const live = [entry('session_started', 'sessionId'), entry('chunk', 'text')];
  const fixture = [entry('session_started', 'sessionId'), entry('chunk', 'body')];
  const diff = firstSchemaDifference(live, fixture);
  assert.notEqual(diff, null);
  assert.equal(diff.index, 1);
  assert.equal(diff.live.fields[0].name, 'text');
  assert.equal(diff.fixture.fields[0].name, 'body');
});

test('a differing field TYPE at the same name is caught', () => {
  const live = [entry('chunk', 'text', 'OBJECT')];
  const fixture = [entry('chunk', 'text', 'STRING')];
  const diff = firstSchemaDifference(live, fixture);
  assert.notEqual(diff, null);
  assert.equal(diff.index, 0);
});

test('the FIRST difference is reported, not a later one', () => {
  const live = [entry('a', 'x'), entry('b', 'y'), entry('c', 'z')];
  const fixture = [entry('a', 'x'), entry('B', 'y'), entry('C', 'z')];
  assert.equal(firstSchemaDifference(live, fixture).index, 1);
});

test('an extra live entry is reported at its index with an undefined fixture side', () => {
  const live = [entry('chunk', 'text'), entry('done', 'reason')];
  const fixture = [entry('chunk', 'text')];
  const diff = firstSchemaDifference(live, fixture);
  assert.equal(diff.index, 1);
  assert.equal(diff.fixture, undefined);
  assert.equal(diff.live.name, 'done');
});

test('an extra fixture entry is reported at its index with an undefined live side', () => {
  const live = [entry('chunk', 'text')];
  const fixture = [entry('chunk', 'text'), entry('done', 'reason')];
  const diff = firstSchemaDifference(live, fixture);
  assert.equal(diff.index, 1);
  assert.equal(diff.live, undefined);
  assert.equal(diff.fixture.name, 'done');
});

test('two empty schemas compare as equal', () => {
  assert.equal(firstSchemaDifference([], []), null);
});

test('key ORDER is not a difference', () => {
  const live = [{ name: 'chunk', fields: [] }];
  const fixture = [{ fields: [], name: 'chunk' }];
  assert.equal(firstSchemaDifference(live, fixture), null);
});

test('the real bundled fixture matches a round-trip of itself', () => {
  // Guards the comparator against the live catalog's actual entry shape rather than the
  // abbreviated one above. A JSON round-trip is exactly what `--live` compares against:
  // the same values, none of the same object identities.
  assert.ok(BUNDLED_SHAPES.length > 0, 'BUNDLED_SHAPES is empty');
  for (const shape of BUNDLED_SHAPES) {
    const roundTripped = JSON.parse(JSON.stringify(shape.eventSchema ?? []));
    assert.equal(
      firstSchemaDifference(roundTripped, shape.eventSchema ?? []),
      null,
      `shape '${shape.id}' did not match its own round-trip`,
    );
  }
});

if (failures.length > 0) {
  console.error(`check-shape-handler-regen.test FAILED (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

console.log(`check-shape-handler-regen.test OK - ${passed} assertions passed.`);
