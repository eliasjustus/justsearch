/**
 * Extract analytics-relevant fields from tool_input and tool_response.
 * Strips content (file bodies, prompt text) — keeps paths, patterns, sizes.
 */

import { roleFor } from './ledger/tool-roles.mjs';

/**
 * Per-tool-name field formatters for `summarizeInput`, grouped under their
 * `lib/ledger/tool-roles.mjs` ROLE (tempdoc 886 §12 PR 5a migration off the
 * hand-rolled `switch (toolName)` this replaced). A role's table is keyed by
 * NAME rather than applied uniformly whenever its members disagree on shape
 * (Edit vs Write; Grep vs Glob; WebSearch vs WebFetch) — collapsing those to
 * one role-wide formatter would print fields the pre-migration switch never
 * printed for that name. `read`'s table has exactly one member because Read
 * is the only Claude tool this module has ever formatted for that role.
 *
 * A name absent from its role's table — including real Claude tools that
 * share a role with a formatted sibling but were never given bespoke fields
 * (`PowerShell` alongside `Bash`'s 'shell'; `Agent` alongside `Task`'s
 * 'spawn'; `NotebookEdit`/`MultiEdit` alongside `Edit`/`Write`'s 'edit') —
 * falls through to the generic `{tool: name}` default in `summarizeInput`,
 * UNCHANGED from before this migration (see `input-summarizer.test.mjs`'s
 * parity snapshot, captured from the pre-migration switch).
 */
const ROLE_FORMATTERS = {
  read: {
    Read: (toolInput) => ({
      file_path: toolInput.file_path ?? null,
      has_offset: toolInput.offset != null,
      has_limit: toolInput.limit != null,
    }),
  },
  edit: {
    Edit: (toolInput) => ({
      file_path: toolInput.file_path ?? null,
      old_string_length: typeof toolInput.old_string === 'string' ? toolInput.old_string.length : null,
      new_string_length: typeof toolInput.new_string === 'string' ? toolInput.new_string.length : null,
      replace_all: toolInput.replace_all ?? false,
    }),
    Write: (toolInput) => ({
      file_path: toolInput.file_path ?? null,
      content_length: typeof toolInput.content === 'string' ? toolInput.content.length : null,
    }),
  },
  shell: {
    Bash: (toolInput) => ({
      command: typeof toolInput.command === 'string' ? toolInput.command.substring(0, 200) : null,
      description: toolInput.description ?? null,
      timeout: toolInput.timeout ?? null,
      run_in_background: toolInput.run_in_background ?? false,
    }),
  },
  search: {
    Grep: (toolInput) => ({
      pattern: toolInput.pattern ?? null,
      path: toolInput.path ?? null,
      output_mode: toolInput.output_mode ?? null,
      type: toolInput.type ?? null,
      glob: toolInput.glob ?? null,
    }),
    Glob: (toolInput) => ({
      pattern: toolInput.pattern ?? null,
      path: toolInput.path ?? null,
    }),
  },
  spawn: {
    Task: (toolInput) => ({
      subagent_type: toolInput.subagent_type ?? null,
      description: toolInput.description ?? null,
      model: toolInput.model ?? null,
      prompt_length: typeof toolInput.prompt === 'string' ? toolInput.prompt.length : null,
      run_in_background: toolInput.run_in_background ?? false,
    }),
  },
  web: {
    WebSearch: (toolInput) => ({ query: toolInput.query ?? null }),
    WebFetch: (toolInput) => {
      let domain = null;
      if (typeof toolInput.url === 'string') {
        try {
          domain = new URL(toolInput.url).hostname;
        } catch {
          domain = toolInput.url.substring(0, 60);
        }
      }
      return { domain };
    },
  },
};

/**
 * Summarize tool_input for a given tool.
 * @param {string} toolName
 * @param {object|undefined} toolInput
 * @returns {object} summary with analytics-relevant fields only
 */
export function summarizeInput(toolName, toolInput) {
  if (!toolInput || typeof toolInput !== 'object' || Array.isArray(toolInput)) return {};

  const role = roleFor('claude-code', toolName);
  const formatter = ROLE_FORMATTERS[role]?.[toolName];
  if (formatter) return formatter(toolInput);

  // Every other name — including a real tool sharing a role with a formatted
  // sibling but never given bespoke fields (see module doc), MCP tools, and
  // any unrecognized name — falls to the generic default.
  if (toolName.startsWith('mcp__')) {
    return { mcp_tool: toolName };
  }
  return { tool: toolName };
}

/**
 * Summarize tool_response for a given tool.
 * Avoids serializing large response objects — extracts known fields only.
 * @param {string} toolName
 * @param {*} toolResponse
 * @returns {object} summary with analytics-relevant fields only
 */
export function summarizeResponse(toolName, toolResponse) {
  if (toolResponse == null) return {};

  if (typeof toolResponse === 'string') {
    return { response_length: toolResponse.length };
  }

  if (typeof toolResponse === 'object') {
    const summary = {};
    if ('exitCode' in toolResponse) summary.exit_code = toolResponse.exitCode;
    if ('success' in toolResponse) summary.success = toolResponse.success;
    if ('filePath' in toolResponse) summary.file_path = toolResponse.filePath;
    // Don't JSON.stringify large response objects just for a length.
    // Count top-level keys as a cheap shape indicator instead.
    if (Object.keys(summary).length === 0) {
      const keys = Object.keys(toolResponse);
      return { response_keys: keys.length };
    }
    return summary;
  }

  return {};
}
