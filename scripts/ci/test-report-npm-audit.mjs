#!/usr/bin/env node

import assert from 'node:assert/strict'

import { collectAuditTarget, isAuditReport, runAudit } from './report-npm-audit.mjs'
import { validateAuditReportAvailability } from '../governance/gates/npm-audit/enforcer.mjs'

const vulnerabilityCounts = {
  info: 0,
  low: 0,
  moderate: 0,
  high: 0,
  critical: 0,
  total: 0,
}
const dependencyCounts = {
  prod: 1,
  dev: 2,
  optional: 0,
  peer: 0,
  peerOptional: 0,
  total: 3,
}
const validAuditPayload = {
  metadata: {
    vulnerabilities: vulnerabilityCounts,
    dependencies: dependencyCounts,
  },
}
const availableTarget = {
  target_id: 'root',
  applicable: true,
  available: true,
  parsed: true,
  exit_code: 1,
  signal: null,
  command_error: null,
  parse_error: null,
  vulnerabilities: vulnerabilityCounts,
  dependencies: dependencyCounts,
}

const calls = []
const timedOut = new Error('audit timed out')
timedOut.code = 'ETIMEDOUT'
const result = runAudit('/fixture/root', {
  platform: 'linux',
  timeoutMs: 1_234,
  spawn(command, args, options) {
    calls.push({ command, args, options })
    return { stdout: '', stderr: '', status: null, signal: 'SIGTERM', error: timedOut }
  },
})

assert.equal(calls.length, 1)
assert.equal(calls[0].command, 'npm')
assert.deepEqual(calls[0].args, ['audit', '--json'])
assert.equal(calls[0].options.timeout, 1_234)
assert.equal(result.parsed, false)
assert.equal(result.available, false)
assert.equal(result.command_error, 'audit timed out')

assert.equal(isAuditReport({ message: 'network timeout', error: {} }), false)
assert.equal(isAuditReport({ metadata: { vulnerabilities: {}, dependencies: {} } }), false)
assert.equal(isAuditReport({ metadata: { vulnerabilities: [], dependencies: [] } }), false)
assert.equal(
  isAuditReport({
    metadata: {
      vulnerabilities: { ...vulnerabilityCounts, high: -1 },
      dependencies: dependencyCounts,
    },
  }),
  false,
)
assert.equal(isAuditReport(validAuditPayload), true)

let missingTargetSpawned = false
const missingRequiredTarget = collectAuditTarget('/fixture/required', {
  exists: () => false,
  spawn: () => {
    missingTargetSpawned = true
  },
})
assert.equal(missingTargetSpawned, false)
assert.equal(missingRequiredTarget.applicable, true)
assert.equal(missingRequiredTarget.available, false)
assert.match(missingRequiredTarget.parse_error, /package-lock\.json is missing/)

const missingRetiredTarget = collectAuditTarget('/fixture/retired', {
  exists: () => false,
  allowMissingLockfile: true,
})
assert.equal(missingRetiredTarget.applicable, false)
assert.equal(missingRetiredTarget.available, true)
assert.equal(missingRetiredTarget.vulnerabilities.total, 0)

const unavailable = validateAuditReportAvailability(
  { targets: [{ target_id: 'root', parsed: false, parse_error: 'Failed to execute npm audit' }] },
  ['root', 'ui-web'],
)
assert.equal(unavailable.length, 2)
assert.ok(unavailable.every((finding) => finding.ruleId === 'npm-audit/report-unavailable'))
assert.match(unavailable[0].message, /root audit evidence is unavailable/)
assert.match(unavailable[1].message, /required audit target is missing/)

const available = validateAuditReportAvailability(
  { targets: [availableTarget] },
  ['root'],
)
assert.deepEqual(available, [], 'npm audit exit 1 is valid evidence when its JSON parsed')

const incompleteCounts = validateAuditReportAvailability(
  { targets: [{ ...availableTarget, vulnerabilities: {} }] },
  ['root'],
)
assert.equal(incompleteCounts.length, 1, 'incomplete count metadata must fail closed')

const requiredSelfDisabled = validateAuditReportAvailability(
  {
    targets: [{
      ...availableTarget,
      applicable: false,
      parsed: false,
      vulnerabilities: vulnerabilityCounts,
      dependencies: { ...dependencyCounts, prod: 0, dev: 0, total: 0 },
    }],
  },
  ['root'],
)
assert.equal(requiredSelfDisabled.length, 1, 'required targets cannot self-declare non-applicable')

const transportErrorJson = validateAuditReportAvailability(
  { targets: [{ target_id: 'root', parsed: true, available: false, parse_error: 'missing metadata' }] },
  ['root'],
)
assert.equal(transportErrorJson.length, 1, 'parseable transport-error JSON must fail closed')

console.log('test-report-npm-audit: PASS')
