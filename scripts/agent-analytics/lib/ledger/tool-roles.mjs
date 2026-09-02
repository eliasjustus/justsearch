/**
 * lib/ledger/tool-roles.mjs — per-harness tool-name -> ToolEvent.role map
 * (tempdoc 886 §12 PR 1). A ROLE, not a tool name, is the neutral axis:
 * "read" means the same cost/behaviour shape whether it came from Claude
 * Code's `Read` or Codex's `read_file`.
 *
 * DATA TABLE, DELIBERATELY. Kept as a plain object export (not buried inside
 * a switch) so PR 5's `lib/input-summarizer.mjs` migration (886 §12) can
 * extend or read these tables directly instead of re-deriving them.
 *
 * `wait` is adapter-level for Claude (a `<task-notification>` carrying user
 * turn has no tool name at all) but IS a real Codex tool name (`wait`,
 * `wait_agent`) and appears as a map value in `CODEX_TOOL_ROLES` below.
 */

export const CLAUDE_TOOL_ROLES = {
  Read: 'read',
  Grep: 'search',
  Glob: 'search',
  Edit: 'edit',
  Write: 'edit',
  NotebookEdit: 'edit',
  MultiEdit: 'edit',
  Bash: 'shell',
  PowerShell: 'shell',
  Agent: 'spawn',
  Task: 'spawn',
  WebFetch: 'web',
  WebSearch: 'web',
};

/**
 * Corpus vocabulary snapshot (independent review, 2026-09-02, 50,259 real
 * Codex calls / tool events): `exec` 12,546, `shell_command` 10,009,
 * `agent_message` 9,203, `apply_patch` 1,504, `wait` 975, `wait_agent` 436,
 * `update_plan` 225, `send_message` 223, `js` 209, `spawn_agent` 182,
 * `list_agents` 124, `run` 76, `followup_task` 50, `view_image` 30. The
 * earlier `shell`/`exec_command` entries here were SPECULATIVE (never
 * observed) and have been removed in favour of the real names `exec`/`run`/
 * `js`. `agent_message` is deliberately NOT in this table: its sampled
 * payloads are plain assistant reply text (the model talking), not tool
 * activity, so `codex-adapter.mjs` never emits a `ToolEvent` for it and a
 * role for it would document a claim the corpus refutes (second-pass review
 * N2, 2026-09-02).
 */
export const CODEX_TOOL_ROLES = {
  exec: 'shell',
  shell_command: 'shell',
  run: 'shell',
  js: 'shell',
  apply_patch: 'edit',
  read_file: 'read',
  view_image: 'read',
  web_search: 'web',
  wait: 'wait',
  wait_agent: 'wait',
  spawn_agent: 'spawn',
  send_message: 'spawn',
  list_agents: 'spawn',
  followup_task: 'spawn',
  update_plan: 'other',
  tool_search: 'other',
};

const MCP_PREFIX = 'mcp__';

/**
 * Resolve `toolName` to a `ToolEvent.role` for `harness`. Unknown tool names
 * (including every `mcp__*` name on Claude — an intentional catch-all, not a
 * gap: MCP servers are arbitrary and per-project, so there is no fixed role
 * to assign one) resolve to `'other'` rather than throwing, matching every
 * other function in this library's "degrade gracefully" posture.
 */
export function roleFor(harness, toolName) {
  if (!toolName) return 'other';
  if (harness === 'claude-code') {
    if (toolName.startsWith(MCP_PREFIX)) return 'other';
    return CLAUDE_TOOL_ROLES[toolName] || 'other';
  }
  if (harness === 'codex-cli') {
    return CODEX_TOOL_ROLES[toolName] || 'other';
  }
  return 'other';
}
