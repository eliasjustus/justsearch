#!/usr/bin/env node

import fs from 'node:fs/promises'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import {
  isNpmAuditPayload,
  NPM_AUDIT_OPTIONAL_TARGET_IDS,
} from './lib/npm-audit-report.mjs'

export const AUDIT_TIMEOUT_MS = 30_000

function parseArgs(argv) {
  const args = { out: 'tmp/npm-audit-report.json' }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--out' && argv[i + 1]) {
      args.out = argv[i + 1]
      i += 1
    }
  }
  return args
}

export function parseAuditJson(outputText) {
  if (!outputText || !outputText.trim()) return null
  try {
    return JSON.parse(outputText)
  } catch {
    const start = outputText.indexOf('{')
    const end = outputText.lastIndexOf('}')
    if (start >= 0 && end > start) {
      const candidate = outputText.slice(start, end + 1)
      try {
        return JSON.parse(candidate)
      } catch {
        return null
      }
    }
    return null
  }
}

function emptyVulnCounts() {
  return { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 }
}

function emptyDependencyCounts() {
  return { prod: 0, dev: 0, optional: 0, peer: 0, peerOptional: 0, total: 0 }
}

function summarizeAuditReport(report) {
  const vulnerabilities = {
    ...emptyVulnCounts(),
    ...(report?.metadata?.vulnerabilities ?? {}),
  }
  const dependencies = {
    ...emptyDependencyCounts(),
    ...(report?.metadata?.dependencies ?? {}),
  }
  return { vulnerabilities, dependencies }
}

export function isAuditReport(report) {
  return isNpmAuditPayload(report)
}

export function runAudit(
  cwd,
  { spawn = spawnSync, platform = process.platform, timeoutMs = AUDIT_TIMEOUT_MS } = {}
) {
  const child =
    platform === 'win32'
      ? spawn('npm audit --json', {
          cwd,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
          shell: true,
          timeout: timeoutMs,
          windowsHide: true,
        })
      : spawn('npm', ['audit', '--json'], {
          cwd,
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
          timeout: timeoutMs,
          windowsHide: true,
        })

  const stdout = child.stdout ?? ''
  const stderr = child.stderr ?? ''
  const parsed = parseAuditJson(stdout) ?? parseAuditJson(stderr)
  const validReport = isAuditReport(parsed)
  const summary = summarizeAuditReport(parsed)

  return {
    cwd,
    applicable: true,
    available: validReport && !child.error && !child.signal,
    exit_code: child.status ?? null,
    signal: child.signal ?? null,
    command_error: child.error ? String(child.error.message ?? child.error) : null,
    parsed: parsed != null,
    parse_error:
      !validReport
        ? child.error
          ? `Failed to execute npm audit: ${child.error.message}`
          : parsed == null
            ? 'Failed to parse npm audit JSON output'
            : 'npm audit returned JSON without vulnerability/dependency metadata'
        : null,
    output_bytes: Buffer.byteLength(stdout, 'utf8') + Buffer.byteLength(stderr, 'utf8'),
    ...summary,
  }
}

export function collectAuditTarget(
  cwd,
  { exists = existsSync, allowMissingLockfile = false, ...runOptions } = {}
) {
  if (!exists(path.join(cwd, 'package-lock.json'))) {
    return {
      cwd,
      applicable: allowMissingLockfile ? false : true,
      available: allowMissingLockfile,
      exit_code: null,
      signal: null,
      command_error: null,
      parsed: false,
      parse_error: allowMissingLockfile ? null : 'package-lock.json is missing',
      output_bytes: 0,
      vulnerabilities: emptyVulnCounts(),
      dependencies: emptyDependencyCounts(),
    }
  }
  return runAudit(cwd, runOptions)
}

function buildMarkdownSummary(report) {
  const lines = [
    '## NPM Audit Summary (advisories are warn-only; transport is fail-closed)',
    '',
    '| Target | High | Moderate | Low | Critical | Total |',
    '|---|---:|---:|---:|---:|---:|',
  ]

  for (const target of report.targets) {
    const v = target.vulnerabilities
    lines.push(
      `| ${target.target_id} | ${v.high} | ${v.moderate} | ${v.low} | ${v.critical} | ${v.total} |`
    )
  }

  lines.push('', 'Policy: vulnerability counts are ratcheted separately; unavailable audit data fails the gate.')

  const parseFailures = report.targets.filter((t) => !t.available)
  if (parseFailures.length > 0) {
    lines.push('', 'Parse warnings:')
    for (const target of parseFailures) {
      lines.push(`- \`${target.target_id}\`: ${target.parse_error}`)
    }
  }

  return `${lines.join('\n')}\n`
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  const repoRoot = process.cwd()

  const targets = [
    { target_id: 'root', cwd: repoRoot },
    { target_id: 'ui-web', cwd: path.join(repoRoot, 'modules', 'ui-web') },
    {
      target_id: 'ssot-tools',
      cwd: path.join(repoRoot, 'SSOT', 'tools'),
      allowMissingLockfile: NPM_AUDIT_OPTIONAL_TARGET_IDS.includes('ssot-tools'),
    },
  ]

  const report = {
    schema: 'npm-audit-report.v1',
    generated_at: new Date().toISOString(),
    policy: 'warn-only',
    targets: targets.map((target) => ({
      target_id: target.target_id,
      ...collectAuditTarget(target.cwd, {
        allowMissingLockfile: target.allowMissingLockfile ?? false,
      }),
    })),
  }

  const outPath = path.resolve(repoRoot, args.out)
  await fs.mkdir(path.dirname(outPath), { recursive: true })
  await fs.writeFile(outPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')

  const markdown = buildMarkdownSummary(report)
  if (process.env.GITHUB_STEP_SUMMARY) {
    await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, markdown, 'utf8')
  }

  process.stdout.write(markdown)
  process.stdout.write(`Saved JSON report to ${outPath}\n`)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(async (error) => {
    const message = error instanceof Error ? error.stack ?? error.message : String(error)
    process.stderr.write(`[report-npm-audit] ${message}\n`)
    process.exitCode = 1
  })
}
