#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const configPath = path.join(repoRoot, '.devcontainer', 'devcontainer.json');
const workflowPath = path.join(repoRoot, '.github', 'workflows', 'onramp-smoke.yml');

test('devcontainer pins the intended CPU-only contributor toolchain', () => {
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  assert.equal(config.image, 'mcr.microsoft.com/devcontainers/base:1-noble');
  assert.deepEqual(config.features['ghcr.io/devcontainers/features/java:1'], {
    version: '25', jdkDistro: 'tem',
  });
  assert.deepEqual(config.features['ghcr.io/devcontainers/features/node:2'], { version: '24' });
  assert.deepEqual(config.features['ghcr.io/devcontainers/features/python:1'], {
    version: '3.13', installTools: false, installJupyterlab: false,
  });
  assert.deepEqual(config.features['ghcr.io/devcontainers/features/rust:1'], {
    version: 'latest', profile: 'minimal',
  });
  assert.equal(config.postCreateCommand,
    'node scripts/setup/bootstrap.mjs && node scripts/dev/doctor.mjs');
  assert.equal(config.waitFor, 'postCreateCommand');
  for (const forbidden of ['runArgs', 'mounts', 'hostRequirements', 'privileged']) {
    assert.equal(config[forbidden], undefined, `${forbidden} must not widen the contributor boundary`);
  }
});

test('manual onramp workflow retains both native and devcontainer proofs', () => {
  const workflow = fs.readFileSync(workflowPath, 'utf8');
  assert.match(workflow, /onramp-devcontainer-smoke:/);
  assert.match(workflow, /onramp-tier0-smoke:/);
  assert.match(workflow, /@devcontainers\/cli@0\.89\.0/);
  assert.match(workflow, /--gpu-availability none/);
  assert.match(workflow, /node scripts\/setup\/bootstrap\.mjs --check/);
  assert.match(workflow, /JUSTSEARCH_MODELS_DIR="\$empty_models" node scripts\/dev\/test-onramp-first-success\.mjs/);
  assert.match(workflow, /if: always\(\)[\s\S]*docker rm --force/);
});
