#!/usr/bin/env node
// A minimal, dependency-free reference MCP server over stdio (newline-delimited JSON-RPC 2.0).
//
// Exists so the MCP-host first consumer (tempdoc 560 §6) has a deterministic external server to
// connect to for integration + live verification — no npx / network dependency. Implements just
// enough of the protocol for the EXECUTABLE axis: `initialize`, `tools/list`, `tools/call`.
//
// Tools:
//   echo(message)       -> echoes the message               (demonstrates argument passthrough)
//   add(a, b)           -> integer sum                       (demonstrates structured args)
//   reverse(text)       -> reversed string
//
// Usage: node scripts/mcp/reference-server.mjs   (speaks JSON-RPC on stdin/stdout)

import { createInterface } from 'node:readline';

const PROTOCOL_VERSION = '2024-11-05';

const TOOLS = [
  {
    name: 'echo',
    description: 'Echo back the provided message.',
    inputSchema: {
      type: 'object',
      properties: { message: { type: 'string', description: 'Text to echo' } },
      required: ['message'],
    },
  },
  {
    name: 'add',
    description: 'Add two integers and return the sum.',
    inputSchema: {
      type: 'object',
      properties: { a: { type: 'integer' }, b: { type: 'integer' } },
      required: ['a', 'b'],
    },
  },
  {
    name: 'reverse',
    description: 'Reverse the characters of a string.',
    inputSchema: {
      type: 'object',
      properties: { text: { type: 'string' } },
      required: ['text'],
    },
  },
  {
    name: 'get-image',
    description: 'Return a small example image (non-text MCP content).',
    inputSchema: { type: 'object', properties: {} },
  },
];

// A 4x4 solid-blue PNG (base64) — a non-text content block to exercise rich rendering.
const TINY_IMAGE_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAEUlEQVR42mNkYPhfz0AEYBxVSAYAQAYBAQ8C2u8AAAAASUVORK5CYII=';

function textResult(text, isError = false) {
  return { content: [{ type: 'text', text: String(text) }], isError };
}

function callTool(name, args = {}) {
  switch (name) {
    case 'echo':
      return textResult(args.message ?? '');
    case 'add':
      return textResult((Number(args.a) || 0) + (Number(args.b) || 0));
    case 'reverse':
      return textResult([...String(args.text ?? '')].reverse().join(''));
    case 'get-image':
      return {
        content: [
          { type: 'text', text: 'a small blue square' },
          { type: 'image', data: TINY_IMAGE_BASE64, mimeType: 'image/png' },
        ],
        isError: false,
      };
    default:
      return null; // -> JSON-RPC method-style error below
  }
}

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function reply(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function replyError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

function handle(msg) {
  // Notifications (no id) are acknowledged by doing nothing.
  if (msg.id === undefined || msg.id === null) return;

  switch (msg.method) {
    case 'initialize':
      reply(msg.id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: 'justsearch-reference-mcp', version: '1.0' },
      });
      return;
    case 'tools/list':
      reply(msg.id, { tools: TOOLS });
      return;
    case 'tools/call': {
      const params = msg.params ?? {};
      const result = callTool(params.name, params.arguments ?? {});
      if (result === null) {
        replyError(msg.id, -32602, `Unknown tool: ${params.name}`);
      } else {
        reply(msg.id, result);
      }
      return;
    }
    default:
      replyError(msg.id, -32601, `Method not found: ${msg.method}`);
  }
}

const rl = createInterface({ input: process.stdin, terminal: false });
rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch {
    return; // ignore non-JSON noise
  }
  try {
    handle(msg);
  } catch (err) {
    if (msg && msg.id != null) replyError(msg.id, -32603, `Internal error: ${err?.message ?? err}`);
  }
});
