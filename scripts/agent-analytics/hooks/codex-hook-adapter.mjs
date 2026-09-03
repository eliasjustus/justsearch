#!/usr/bin/env node
/**
 * Codex -> shared JustSearch hook adapter.
 *
 * Codex and Claude share the policy/binding authority in
 * governance/agent-hooks.v1.json, but not their event payload schemas. Codex
 * reports patches as `apply_patch`, uses one PostToolUse event for both shell
 * success and failure, and may launch matching handlers concurrently. This
 * adapter is the single Codex handler for an event: it resolves matching
 * manifest bindings, normalizes inputs, executes them serially, and combines
 * their decisions into one Codex hook response.
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { hooksDisabled, readJsonStdin, repoRoot } from '../lib/hook-base.mjs';

const MANIFEST_PATH = path.join(repoRoot, 'governance', 'agent-hooks.v1.json');

// These policies have no honest Codex projection. They remain catalogued and
// live in Claude; omission here is explicit rather than a silent no-op.
export const CODEX_EXCLUDED_HOOKS = new Map([
  ['subagent-model-guard', 'Codex uses project agent roles and inherited/default model configuration, not Claude model aliases.'],
  ['spawn-cost-hint', 'Codex rollout lineage does not currently expose a reliable completed-spawn-to-parent-tool join.'],
  ['taskcreate-guard', 'TaskCreate is a Claude-only tool shape.'],
]);

const PATH_SENSITIVE_HOOKS = new Set([
  'intervene',
  'consult-doc-hint',
  'docs-regen-hint',
  'ssot-hint',
  'ui-shot-hint',
  'test-edit-hint',
  'stress-test-hint',
  'seam-hint',
  'search-engine-hint',
  'mcpb-repack-hint',
  'lockfile-hint',
  'governance-hint',
  'tempdoc-age-hint',
  'edit-reread-hint',
]);

export function patchTargets(command) {
  if (typeof command !== 'string') return [];
  const targets = [];
  const seen = new Set();
  const re = /^(?:\*\*\*\s+(Add|Update|Delete)\s+File:\s*(.+?)|\*\*\*\s+Move to:\s*(.+?))\s*$/gmi;
  for (const match of command.matchAll(re)) {
    const action = match[1]?.toLowerCase() ?? 'update';
    const filePath = (match[2] ?? match[3]).trim();
    const key = `${action}:${filePath}`;
    if (seen.has(key)) continue;
    seen.add(key);
    targets.push({
      filePath,
      toolName: action === 'add' ? 'Write' : 'Edit',
    });
  }
  return targets;
}

export function toolAliases(toolName) {
  const aliases = new Set([toolName].filter(Boolean));
  if (toolName === 'apply_patch') {
    aliases.add('Edit');
    aliases.add('Write');
  }
  if (toolName === 'spawn_agent') aliases.add('Agent');
  if (toolName === 'exec_command' || toolName === 'shell_command' || toolName === 'exec') aliases.add('Bash');
  return [...aliases];
}

export function matcherMatches(matcher, toolName) {
  if (matcher == null || matcher === '') return true;
  let re;
  try {
    re = new RegExp(matcher, 'i');
  } catch {
    return false;
  }
  return toolAliases(toolName).some((name) => re.test(name));
}

export function extractExitCode(response) {
  if (response == null) return null;
  if (typeof response === 'object') {
    for (const key of ['exitCode', 'exit_code', 'statusCode', 'status_code']) {
      if (Number.isInteger(response[key])) return response[key];
    }
    if (response.metadata && typeof response.metadata === 'object') {
      const nested = extractExitCode(response.metadata);
      if (nested != null) return nested;
    }
  }
  const text = typeof response === 'string' ? response : JSON.stringify(response);
  for (const pattern of [
    /(?:process\s+)?exit(?:ed)?\s+(?:with\s+)?(?:code\s+)?(-?\d+)/i,
    /"exit_code"\s*:\s*(-?\d+)/i,
  ]) {
    const match = pattern.exec(text);
    if (match) return Number.parseInt(match[1], 10);
  }
  return null;
}

function normalizedToolName(name) {
  if (name === 'apply_patch') return 'Edit';
  if (name === 'spawn_agent') return 'Agent';
  if (name === 'exec_command' || name === 'shell_command' || name === 'exec') return 'Bash';
  return name;
}

function normalizedInput(input, target = null) {
  const out = {
    ...input,
    harness: 'codex-cli',
    tool_name: target?.toolName ?? normalizedToolName(input.tool_name),
    tool_input: { ...(input.tool_input ?? {}) },
  };
  if (target) out.tool_input.file_path = target.filePath;
  if (input.hook_event_name === 'PostToolUse') {
    const exitCode = extractExitCode(input.tool_response);
    if (exitCode != null) {
      out.tool_response = { ...(typeof input.tool_response === 'object' ? input.tool_response : {}), exitCode };
    }
  }
  return out;
}

function inputsForHook(hookId, input) {
  if (input.tool_name !== 'apply_patch' || !PATH_SENSITIVE_HOOKS.has(hookId)) {
    return [normalizedInput(input)];
  }
  const targets = patchTargets(input.tool_input?.command);
  return targets.length > 0 ? targets.map((target) => normalizedInput(input, target)) : [normalizedInput(input)];
}

function parseOutput(stdout) {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return { additionalContext: trimmed };
  }
}

function invokeHook({ hookDir, file, timeout }, input) {
  const script = path.join(repoRoot, hookDir, file);
  const result = spawnSync(process.execPath, [script], {
    cwd: repoRoot,
    encoding: 'utf8',
    input: JSON.stringify(input),
    timeout: Math.max(1, Math.round((timeout ?? 5) * 1000)),
    env: { ...process.env, JUSTSEARCH_AGENT_HARNESS: 'codex-cli' },
    windowsHide: true,
  });
  return {
    status: result.status,
    signal: result.signal,
    stderr: result.stderr ?? '',
    output: parseOutput(result.stdout ?? ''),
    error: result.error ?? null,
  };
}

function mergeResult(state, result) {
  if (result.error || result.signal) {
    state.systemMessages.push(`Codex hook adapter could not run a shared hook: ${result.error?.message ?? result.signal}`);
  }
  if (result.status != null && result.status !== 0 && result.status !== 2) {
    state.systemMessages.push(
      `A shared JustSearch hook exited ${result.status}: ${result.stderr.trim() || 'no diagnostic was emitted'}`,
    );
  }
  if (result.status === 2) {
    state.denials.push(result.stderr.trim() || 'Blocked by a JustSearch repository hook.');
  }
  const output = result.output;
  if (!output) return;
  if (output.systemMessage) state.systemMessages.push(output.systemMessage);
  if (output.additionalContext) state.context.push(output.additionalContext);
  if (output.decision === 'block') state.blocks.push(output.reason || 'Repository policy requires another step.');
  if (output.continue === false) state.continue = false;
  const specific = output.hookSpecificOutput;
  if (!specific) return;
  if (specific.additionalContext) state.context.push(specific.additionalContext);
  if (specific.permissionDecision) state.permissionDecision = specific.permissionDecision;
  if (specific.permissionDecisionReason) state.permissionDecisionReason = specific.permissionDecisionReason;
  if (specific.updatedInput) state.updatedInput = { ...(state.updatedInput ?? {}), ...specific.updatedInput };
}

export function buildResponse(eventName, state) {
  const out = {};
  if (state.systemMessages.length > 0) out.systemMessage = state.systemMessages.join('\n');
  if (state.blocks.length > 0) {
    out.decision = 'block';
    out.reason = state.blocks.join('\n\n');
  }
  if (state.continue === false) out.continue = false;
  const specific = { hookEventName: eventName };
  if (state.context.length > 0) specific.additionalContext = [...new Set(state.context)].join('\n\n');
  if (state.permissionDecision) specific.permissionDecision = state.permissionDecision;
  if (state.permissionDecisionReason) specific.permissionDecisionReason = state.permissionDecisionReason;
  if (state.updatedInput) specific.updatedInput = state.updatedInput;
  if (Object.keys(specific).length > 1) out.hookSpecificOutput = specific;
  return out;
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input?.hook_event_name) return;

  const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, 'utf8'));
  const groups = manifest.bindings?.[input.hook_event_name] ?? [];
  const state = {
    denials: [], blocks: [], context: [], systemMessages: [],
    permissionDecision: null, permissionDecisionReason: null, updatedInput: null, continue: true,
  };

  for (const group of groups) {
    if (!matcherMatches(group.matcher, input.tool_name)) continue;
    for (const binding of group.hooks ?? []) {
      if (CODEX_EXCLUDED_HOOKS.has(binding.hookId)) continue;
      const catalog = manifest.hooks?.[binding.hookId];
      if (!catalog) continue;
      for (const adapted of inputsForHook(binding.hookId, input)) {
        mergeResult(state, invokeHook({ hookDir: manifest.hookDir, file: catalog.file, timeout: binding.timeout }, adapted));
      }
    }
  }

  if (state.denials.length > 0) {
    process.stderr.write(state.denials.join('\n\n'));
    process.exit(2);
  }
  const response = buildResponse(input.hook_event_name, state);
  if (Object.keys(response).length > 0) process.stdout.write(JSON.stringify(response));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`[codex-hook-adapter] ${error.message}`);
    process.exit(1);
  });
}
