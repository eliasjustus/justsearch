import assert from 'node:assert/strict';
import { runApiProjectionChecks } from './check-api-client-regen.mjs';

function statuses(...values) {
  const calls = [];
  return {
    calls,
    run(command, args) {
      calls.push({ command, args });
      return values.shift();
    },
  };
}

{
  const fixture = statuses({ status: 0 }, { status: 0 });
  assert.equal(runApiProjectionChecks(fixture.run), 0);
  assert.equal(fixture.calls.length, 2);
  assert.match(fixture.calls[0].args[0], /gen-api-client\.mjs$/);
  assert.match(fixture.calls[1].args[0], /check-reference-client-openapi-regen\.mjs$/);
}

{
  const fixture = statuses({ status: 7 }, { status: 0 });
  assert.equal(runApiProjectionChecks(fixture.run), 7);
  assert.equal(fixture.calls.length, 1, 'OpenAPI check must not mask a client failure');
}

{
  const fixture = statuses({ status: 0 }, { status: 9 });
  assert.equal(runApiProjectionChecks(fixture.run), 9);
  assert.equal(fixture.calls.length, 2, 'OpenAPI failure must propagate from the CI-wired path');
}

{
  const fixture = statuses({ status: null, error: new Error('spawn failed') });
  assert.equal(runApiProjectionChecks(fixture.run), 1);
}

console.log('check-api-client-regen.test: all 4 checks passed');
