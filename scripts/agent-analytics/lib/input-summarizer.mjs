/**
 * Extract analytics-relevant fields from tool_input and tool_response.
 * Strips content (file bodies, prompt text) — keeps paths, patterns, sizes.
 */

/**
 * Summarize tool_input for a given tool.
 * @param {string} toolName
 * @param {object|undefined} toolInput
 * @returns {object} summary with analytics-relevant fields only
 */
export function summarizeInput(toolName, toolInput) {
  if (!toolInput || typeof toolInput !== 'object' || Array.isArray(toolInput)) return {};

  switch (toolName) {
    case 'Read':
      return {
        file_path: toolInput.file_path ?? null,
        has_offset: toolInput.offset != null,
        has_limit: toolInput.limit != null,
      };

    case 'Edit':
      return {
        file_path: toolInput.file_path ?? null,
        old_string_length: typeof toolInput.old_string === 'string' ? toolInput.old_string.length : null,
        new_string_length: typeof toolInput.new_string === 'string' ? toolInput.new_string.length : null,
        replace_all: toolInput.replace_all ?? false,
      };

    case 'Write':
      return {
        file_path: toolInput.file_path ?? null,
        content_length: typeof toolInput.content === 'string' ? toolInput.content.length : null,
      };

    case 'Bash':
      return {
        command: typeof toolInput.command === 'string' ? toolInput.command.substring(0, 200) : null,
        description: toolInput.description ?? null,
        timeout: toolInput.timeout ?? null,
        run_in_background: toolInput.run_in_background ?? false,
      };

    case 'Grep':
      return {
        pattern: toolInput.pattern ?? null,
        path: toolInput.path ?? null,
        output_mode: toolInput.output_mode ?? null,
        type: toolInput.type ?? null,
        glob: toolInput.glob ?? null,
      };

    case 'Glob':
      return {
        pattern: toolInput.pattern ?? null,
        path: toolInput.path ?? null,
      };

    case 'Task':
      return {
        subagent_type: toolInput.subagent_type ?? null,
        description: toolInput.description ?? null,
        model: toolInput.model ?? null,
        prompt_length: typeof toolInput.prompt === 'string' ? toolInput.prompt.length : null,
        run_in_background: toolInput.run_in_background ?? false,
      };

    case 'WebSearch':
      return {
        query: toolInput.query ?? null,
      };

    case 'WebFetch': {
      let domain = null;
      if (typeof toolInput.url === 'string') {
        try {
          domain = new URL(toolInput.url).hostname;
        } catch {
          domain = toolInput.url.substring(0, 60);
        }
      }
      return { domain };
    }

    case 'TaskCreate':
    case 'TaskUpdate':
    case 'TaskGet':
    case 'TaskList':
    case 'TaskOutput':
      return { tool: toolName };

    case 'EnterPlanMode':
    case 'ExitPlanMode':
    case 'AskUserQuestion':
    case 'Skill':
      return { tool: toolName };

    default:
      // MCP tools and anything unknown — tool name only
      if (toolName.startsWith('mcp__')) {
        return { mcp_tool: toolName };
      }
      return { tool: toolName };
  }
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
