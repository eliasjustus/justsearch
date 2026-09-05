// Hand-authored input for generate-dev-mcp-runtime.mjs.
//
// Keep this boundary deliberately small: the committed runtime projection contains only the
// third-party code needed before the repository-local MCP application can initialize.
export { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
export { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
export * as z from 'zod/v4';
