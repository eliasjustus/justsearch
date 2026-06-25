#!/usr/bin/env node

/**
 * Slice 487 §3.1 — URL-emission accuracy probe (standalone runner).
 *
 * Replaces the legacy Agent Battery harness (broken since 2026-03-16; see
 * commit a9c484f59 + slice 487 §8 decision log). Self-contained: depends
 * only on the URL scorer (`./agent-battery-url-scorer.mjs`) plus Node
 * built-ins. No corpus governance, no DAG runner, no scorecard cross-deps.
 *
 * Subcommands:
 *   render-prompt — fetch live catalogs, render the url-emitter systemPrompt.
 *   run           — fire scenarios against /api/chat/agent and score.
 *   summarize     — print a Markdown scorecard from a results JSON file.
 *
 * Prerequisites:
 *   - JustSearch backend running (the runner does NOT manage lifecycle).
 *   - Qwen3.5-9B activated via /api/ai/runtime/activate.
 *   - At least one corpus indexed (the model may reference indexed content
 *     to choose URL args, though navigation-only scenarios do not require it).
 */

import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { parseArgs as nodeParseArgs } from 'node:util';

import { evaluateUrlEmission } from './agent-battery-url-scorer.mjs';

// ---------------------------------------------------------------------------
// HTTP helpers (inline; no scripts/lib dependency)
// ---------------------------------------------------------------------------

function httpGetText(urlStr, { timeoutMs = 5000, maxBytes = 2_000_000 } = {}) {
  return new Promise((resolve) => {
    const u = new URL(urlStr);
    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port) || 80,
        path: u.pathname + u.search,
        method: 'GET',
        timeout: timeoutMs,
        headers: { Accept: 'application/json' },
      },
      (res) => {
        const chunks = [];
        let bytes = 0;
        res.on('data', (c) => {
          bytes += c.length;
          if (bytes > maxBytes) {
            req.destroy();
            resolve({ ok: false, statusCode: res.statusCode, text: '', error: 'too_large' });
            return;
          }
          chunks.push(c);
        });
        res.on('end', () => {
          resolve({
            ok: res.statusCode >= 200 && res.statusCode < 300,
            statusCode: res.statusCode,
            text: Buffer.concat(chunks).toString('utf8'),
          });
        });
        res.on('error', (err) => resolve({ ok: false, error: err.message }));
      },
    );
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, error: 'timeout' });
    });
    req.on('error', (err) => resolve({ ok: false, error: err.message }));
    req.end();
  });
}

function safeJsonParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function httpPostJson(urlStr, body, { timeoutMs = 60_000, maxBytes = 2_000_000 } = {}) {
  return new Promise((resolve) => {
    const u = new URL(urlStr);
    const bodyStr = JSON.stringify(body);
    let bytes = 0;
    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port) || 80,
        path: u.pathname + u.search,
        method: 'POST',
        timeout: timeoutMs,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(bodyStr),
          Accept: 'application/json',
        },
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => {
          bytes += c.length;
          if (bytes > maxBytes) {
            req.destroy();
            resolve({ ok: false, statusCode: res.statusCode, error: 'too_large' });
            return;
          }
          chunks.push(c);
        });
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          resolve({
            ok: res.statusCode >= 200 && res.statusCode < 300,
            statusCode: res.statusCode,
            text,
          });
        });
        res.on('error', (err) => resolve({ ok: false, error: err.message }));
      },
    );
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, error: 'timeout' });
    });
    req.on('error', (err) => resolve({ ok: false, error: err.message }));
    req.write(bodyStr);
    req.end();
  });
}

/**
 * POST to llama-server's OpenAI-compatible /v1/chat/completions endpoint.
 *
 * Returns `{finalResponse, durationMs, error?}` mirroring the SSE-consumer
 * shape so the scorer can stay unchanged. Non-streaming — the entire
 * completion arrives in one JSON body once the model finishes.
 */
async function callLlamaServer(serverUrl, { systemPrompt, userPrompt, maxTokens = 256, temperature = 0.0 }, { timeoutMs = 60_000 } = {}) {
  const startMs = Date.now();
  const resp = await httpPostJson(
    new URL('/v1/chat/completions', serverUrl).toString(),
    {
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt },
      ],
      max_tokens: maxTokens,
      temperature,
      stream: false,
    },
    { timeoutMs, maxBytes: 4_000_000 },
  );
  const durationMs = Date.now() - startMs;
  if (!resp.ok) {
    return {
      finalResponse: '',
      durationMs,
      error: { message: resp.error || `HTTP_${resp.statusCode}`, details: (resp.text || '').slice(0, 500) },
    };
  }
  const parsed = safeJsonParse(resp.text);
  if (!parsed) {
    return { finalResponse: '', durationMs, error: { message: 'invalid_json', details: resp.text.slice(0, 500) } };
  }
  const content = parsed.choices?.[0]?.message?.content || '';
  const finishReason = parsed.choices?.[0]?.finish_reason || null;
  return {
    finalResponse: content,
    durationMs,
    finishReason,
    usage: parsed.usage || null,
    error: null,
  };
}

/**
 * POST to a streaming endpoint and accumulate text from SSE `chunk` events.
 * Returns when the `done` event fires, the stream closes, or timeout hits.
 *
 * Note: kept for reference; the URL probe targets llama-server directly via
 * `callLlamaServer` to bypass JustSearch agent-loop tool injection (see
 * slice 487 §3.1 probe trace 2026-05-12).
 */
function consumeAgentSse(streamUrl, body, { timeoutMs = 120_000, maxBytes = 2_000_000 } = {}) {
  return new Promise((resolve) => {
    const startMs = Date.now();
    const transcript = {
      sessionId: null,
      finalResponse: '',
      chunks: [],
      events: [],
      durationMs: 0,
      error: null,
    };
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      transcript.durationMs = Date.now() - startMs;
      if (!transcript.finalResponse) transcript.finalResponse = transcript.chunks.join('');
      resolve(transcript);
    };

    const u = new URL(streamUrl);
    const bodyStr = JSON.stringify(body);
    let buffer = '';
    let currentEvent = '';
    let currentData = '';
    let bytes = 0;

    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port) || 80,
        path: u.pathname + u.search,
        method: 'POST',
        timeout: timeoutMs,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(bodyStr),
          Accept: 'text/event-stream',
        },
      },
      (res) => {
        if (res.statusCode !== 200) {
          const errChunks = [];
          res.on('data', (c) => errChunks.push(c));
          res.on('end', () => {
            transcript.error = {
              message: `HTTP_${res.statusCode}`,
              details: Buffer.concat(errChunks).toString('utf8').slice(0, 500),
            };
            finish();
          });
          return;
        }
        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          bytes += Buffer.byteLength(chunk, 'utf8');
          if (bytes > maxBytes) {
            transcript.error = { message: 'response_too_large' };
            req.destroy();
            finish();
            return;
          }
          buffer += chunk;
          const lines = buffer.split('\n');
          buffer = lines.pop();
          for (const raw of lines) {
            const line = raw.replace(/\r$/, '');
            if (line.startsWith('event:')) {
              currentEvent = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
              currentData = line.slice(5).trim();
            } else if (line === '') {
              let data = {};
              if (currentData) {
                try { data = JSON.parse(currentData); } catch { /* ignore */ }
              }
              transcript.events.push({ event: currentEvent, dataKeys: Object.keys(data || {}) });
              switch (currentEvent) {
                case 'session_started':
                  transcript.sessionId = data.sessionId || null;
                  break;
                case 'chunk':
                  if (typeof data.text === 'string') transcript.chunks.push(data.text);
                  break;
                case 'done':
                  transcript.finalResponse = data.finalResponse || transcript.chunks.join('');
                  finish();
                  return;
                case 'error':
                  transcript.error = {
                    message: data.error || data.errorCode || 'unknown_error',
                    code: data.errorCode,
                  };
                  if (!transcript.finalResponse) {
                    transcript.finalResponse = transcript.chunks.join('');
                  }
                  finish();
                  return;
              }
              currentEvent = '';
              currentData = '';
            }
          }
        });
        res.on('end', () => {
          if (!settled && !transcript.error) {
            transcript.error = { message: 'stream_closed_unexpectedly' };
          }
          finish();
        });
        res.on('error', (err) => {
          transcript.error = { message: err.message };
          finish();
        });
      },
    );
    req.on('timeout', () => {
      transcript.error = { message: 'timeout' };
      req.destroy();
      finish();
    });
    req.on('error', (err) => {
      transcript.error = { message: err.message };
      finish();
    });
    req.write(bodyStr);
    req.end();
  });
}

// ---------------------------------------------------------------------------
// Catalog fetch
// ---------------------------------------------------------------------------

async function fetchCatalog(apiBaseUrl) {
  const opsResp = await httpGetText(
    new URL('/api/registry/operations', apiBaseUrl).toString(),
    { timeoutMs: 10_000, maxBytes: 1_000_000 },
  );
  const surfResp = await httpGetText(
    new URL('/api/registry/surfaces', apiBaseUrl).toString(),
    { timeoutMs: 10_000, maxBytes: 1_000_000 },
  );
  if (!opsResp.ok) throw new Error(`Failed to fetch operations: ${opsResp.error || opsResp.statusCode}`);
  if (!surfResp.ok) throw new Error(`Failed to fetch surfaces: ${surfResp.error || surfResp.statusCode}`);
  const opsBody = safeJsonParse(opsResp.text) || {};
  const surfBody = safeJsonParse(surfResp.text) || {};
  // Live response shape uses `entries` (polymorphic, with type discriminator).
  const opEntries = Array.isArray(opsBody.entries) ? opsBody.entries : [];
  const surfEntries = Array.isArray(surfBody.entries) ? surfBody.entries : [];
  return {
    operations: opEntries.filter((e) => !e.type || e.type === 'operation'),
    surfaces: surfEntries,
  };
}

// ---------------------------------------------------------------------------
// systemPrompt rendering
// ---------------------------------------------------------------------------

function deriveTitleFromKey(labelKey, fallbackId) {
  // labelKey looks like 'ops.bulk-reindex.label' or
  // 'registry-surface.library-surface.label' — extract the human-meaningful
  // middle segment as a fallback when no live i18n bundle is loaded.
  if (typeof labelKey === 'string' && labelKey.length > 0) {
    const parts = labelKey.split('.');
    if (parts.length >= 2) return parts[parts.length - 2].replace(/-/g, ' ');
  }
  // Last resort: derive from id ('core.bulk-reindex' -> 'bulk reindex')
  if (typeof fallbackId === 'string') {
    const tail = fallbackId.split('.').pop() || fallbackId;
    return tail.replace(/-/g, ' ');
  }
  return '';
}

function renderOpDescriptor(op) {
  const id = op.id || '<unknown>';
  const title =
    op.presentation?.title ||
    deriveTitleFromKey(op.presentation?.labelKey, id);
  const description =
    op.presentation?.description ||
    op.presentation?.descriptionKey?.split('.').slice(-2)[0]?.replace(/-/g, ' ') ||
    '';
  let argsLine = '';
  const inputs = op.intf?.inputs;
  if (inputs && typeof inputs === 'object' && inputs.properties) {
    const required = new Set(Array.isArray(inputs.required) ? inputs.required : []);
    const parts = [];
    for (const [name, schema] of Object.entries(inputs.properties)) {
      let t;
      if (schema && typeof schema === 'object') {
        if (Array.isArray(schema.enum) && schema.enum.length > 0) {
          // Surface enum values so the model knows the allowed set.
          t = `enum(${schema.enum.join('|')})`;
        } else if (schema.type === 'array' && schema.items?.type) {
          if (Array.isArray(schema.items.enum) && schema.items.enum.length > 0) {
            t = `enum(${schema.items.enum.join('|')})[]`;
          } else {
            t = `${schema.items.type}[]`;
          }
        } else {
          t = schema.type || 'any';
        }
      } else {
        t = 'any';
      }
      parts.push(`${name}:${t}${required.has(name) ? '' : '?'}`);
    }
    argsLine = parts.join(', ');
  }
  const confirm = op.policy?.confirm?.type;
  const audience = op.audience;
  const lines = [`op:    ${id}`];
  if (title) lines.push(`title: ${title}`);
  if (argsLine) lines.push(`args:  ${argsLine}`);
  if (audience || confirm) {
    lines.push(`note:  audience=${audience || '?'}${confirm ? `, confirm=${confirm}` : ''}`);
  }
  return lines.join('\n');
}

function renderSurfaceDescriptor(surface) {
  const id = surface.id || '<unknown>';
  const title =
    surface.presentation?.title ||
    deriveTitleFromKey(surface.presentation?.labelKey, id);
  const lines = [`surface: ${id}`];
  if (title) lines.push(`title:   ${title}`);
  return lines.join('\n');
}

export function renderSystemPrompt(catalog, { includeOpIds = null, includeSurfaceIds = null } = {}) {
  const ops = catalog.operations.filter(
    (op) => !includeOpIds || includeOpIds.includes(op.id),
  );
  const surfaces = catalog.surfaces.filter(
    (s) => !includeSurfaceIds || includeSurfaceIds.includes(s.id),
  );

  const preamble = `You are JustSearch's local assistant. The user's chrome will auto-route any \`justsearch://...\` URLs you emit in your response. The user does not click these URLs — the app routes them automatically. Destructive actions surface a confirmation gate at the destination.

## URL grammar

\`\`\`
justsearch://surface/<surfaceId>[?key=value&...]    # navigate to a view
justsearch://op/<opId>[?argName=value&...]          # perform an action
\`\`\`

Use the IDs and arg names declared in the catalog below. Encode special characters in args. Emit exactly one URL per action you want to perform, in the order you want them to execute. URLs may appear in Markdown link form \`[label](justsearch://...)\` or as bare URLs.

**Arg encoding rules:**
- For \`array\`-typed args (shown as \`name:type[]\`), repeat the key once per value: \`?ids=a&ids=b&ids=c\`.
- For \`enum(...)\`-typed args, the value MUST be one of the listed options exactly (case-sensitive).
- Omit optional args (shown with a trailing \`?\`) when their natural default is what the user wants.

## Available actions

`;

  const opBlocks = ops.map(renderOpDescriptor).join('\n\n');
  const surfaceBlocks = surfaces.map(renderSurfaceDescriptor).join('\n\n');

  return `${preamble}${opBlocks}\n\n${surfaceBlocks}\n`;
}

// ---------------------------------------------------------------------------
// render-prompt subcommand
// ---------------------------------------------------------------------------

async function cmdRenderPrompt(args) {
  const apiBaseUrl = args['api-base-url'];
  if (!apiBaseUrl) throw new Error('--api-base-url is required');
  const outPath = args.out || null;
  const catalog = await fetchCatalog(apiBaseUrl);
  const prompt = renderSystemPrompt(catalog);
  const summary = {
    ok: true,
    operationCount: catalog.operations.length,
    surfaceCount: catalog.surfaces.length,
    promptChars: prompt.length,
    promptApproxTokens: Math.ceil(prompt.length / 4),
    outPath: outPath || '(stdout)',
  };
  if (outPath) {
    await fs.writeFile(outPath, prompt, 'utf8');
    process.stdout.write(JSON.stringify(summary) + '\n');
  } else {
    process.stdout.write(prompt);
    process.stderr.write(JSON.stringify(summary) + '\n');
  }
}

// ---------------------------------------------------------------------------
// run subcommand
// ---------------------------------------------------------------------------

async function cmdRun(args) {
  const apiBaseUrl = args['api-base-url'];
  if (!apiBaseUrl) throw new Error('--api-base-url is required');
  const llamaServerUrl = args['llama-server-url'] || 'http://127.0.0.1:8082';
  // Slice 487 Phase 2.5 (Gate G2): when --substrate-url is supplied, route the
  // probe through the substrate-driven /api/chat/url-emit endpoint instead of
  // hitting llama-server directly. The substrate-driven path runs the same
  // tool-less LLM emission shape via NavigateChatShape's URLEmissionCapability
  // (PromptContributor + StreamConsumer), then the URLExtractor dispatches the
  // emitted URLs through the BackendIntentRouter. The probe measures the
  // LLM-emission accuracy AFTER substrate plumbing, confirming the 96.8%
  // baseline transfers to production-equivalent wiring.
  const substrateUrl = args['substrate-url'];
  const scenariosPath = args.scenarios;
  if (!scenariosPath) throw new Error('--scenarios is required');
  const outPath = args.out;
  if (!outPath) throw new Error('--out is required');
  const scenarioTimeoutMs = Number(args['scenario-timeout-ms'] || 60_000);
  const maxTokens = Number(args['max-tokens'] || 256);

  const scenariosDoc = JSON.parse(await fs.readFile(scenariosPath, 'utf8'));
  const scenarios = Array.isArray(scenariosDoc.scenarios) ? scenariosDoc.scenarios : [];
  if (scenarios.length === 0) throw new Error(`No scenarios found in ${scenariosPath}`);

  // The scenarios file may carry the systemPrompt inline, or reference it
  // via systemPromptPath. The renderer's output is the canonical source.
  let systemPrompt = scenariosDoc.systemPrompt;
  if (!systemPrompt && scenariosDoc.systemPromptPath) {
    const resolved = path.isAbsolute(scenariosDoc.systemPromptPath)
      ? scenariosDoc.systemPromptPath
      : path.join(path.dirname(scenariosPath), scenariosDoc.systemPromptPath);
    systemPrompt = await fs.readFile(resolved, 'utf8');
  }
  if (!systemPrompt) throw new Error('scenarios file lacks systemPrompt and systemPromptPath');

  // Fetch catalog once for scoring all scenarios. We hit the JustSearch
  // backend (not llama-server) for the catalog — it is the source of truth
  // for op-ids and inputSchemas. llama-server only sees the rendered prompt.
  const catalog = await fetchCatalog(apiBaseUrl);

  const results = [];
  const startedAt = new Date().toISOString();
  for (let i = 0; i < scenarios.length; i++) {
    const scenario = scenarios[i];
    process.stderr.write(`[${i + 1}/${scenarios.length}] ${scenario.id} ... `);
    const transcript = substrateUrl
      ? await consumeAgentSse(
          substrateUrl,
          { prompt: scenario.prompt, maxTokens },
          { timeoutMs: scenarioTimeoutMs },
        )
      : await callLlamaServer(
          llamaServerUrl,
          { systemPrompt, userPrompt: scenario.prompt, maxTokens, temperature: 0.0 },
          { timeoutMs: scenarioTimeoutMs },
        );
    const urlAxes = evaluateUrlEmission(scenario, transcript, catalog);
    const ok =
      urlAxes.urlPresent &&
      urlAxes.schemeValid &&
      urlAxes.opIdResolves &&
      urlAxes.argsValidate !== false &&
      urlAxes.semanticMatch !== false;
    results.push({
      id: scenario.id,
      prompt: scenario.prompt,
      expectedURL: scenario.expectedURL || null,
      expectedURLs: scenario.expectedURLs || null,
      finalResponse: transcript.finalResponse,
      durationMs: transcript.durationMs,
      error: transcript.error,
      urlAxes,
      pass: ok,
    });
    process.stderr.write(`${ok ? 'OK' : 'FAIL'} (${transcript.durationMs}ms) ${urlAxes.failures.join(' / ')}\n`);
  }
  const finishedAt = new Date().toISOString();

  const out = {
    schema: 'url-probe-results.v1',
    startedAt,
    finishedAt,
    apiBaseUrl,
    llamaServerUrl,
    scenarioCount: scenarios.length,
    catalogSize: {
      operations: catalog.operations.length,
      surfaces: catalog.surfaces.length,
    },
    results,
  };
  await fs.writeFile(outPath, JSON.stringify(out, null, 2), 'utf8');
  process.stderr.write(`\nWrote ${outPath}\n`);
}

// ---------------------------------------------------------------------------
// summarize subcommand
// ---------------------------------------------------------------------------

function pct(numer, denom) {
  if (!Number.isFinite(numer) || !Number.isFinite(denom) || denom === 0) return '—';
  return `${((numer / denom) * 100).toFixed(1)}% (${numer}/${denom})`;
}

async function cmdSummarize(args) {
  const resultsPath = args.results;
  if (!resultsPath) throw new Error('--results is required');
  const doc = JSON.parse(await fs.readFile(resultsPath, 'utf8'));
  const results = Array.isArray(doc.results) ? doc.results : [];
  const n = results.length;

  const urlPresent = results.filter((r) => r.urlAxes?.urlPresent === true).length;
  const schemeValid = results.filter((r) => r.urlAxes?.schemeValid === true).length;
  const opIdResolves = results.filter((r) => r.urlAxes?.opIdResolves === true).length;

  const argsScored = results.filter((r) => r.urlAxes?.argsValidate !== null);
  const argsValid = argsScored.filter((r) => r.urlAxes?.argsValidate === true).length;

  const semanticScored = results.filter((r) => r.urlAxes?.semanticMatch !== null);
  const semanticMatch = semanticScored.filter((r) => r.urlAxes?.semanticMatch === true).length;

  const passed = results.filter((r) => r.pass === true).length;

  const lines = [];
  lines.push(`# URL emission probe — results`);
  lines.push('');
  lines.push(`- run:           ${doc.startedAt} → ${doc.finishedAt}`);
  lines.push(`- backend:       ${doc.apiBaseUrl}`);
  lines.push(`- catalog:       ${doc.catalogSize?.operations} ops, ${doc.catalogSize?.surfaces} surfaces`);
  lines.push(`- scenarios:     ${n}`);
  lines.push(`- overall pass:  ${pct(passed, n)}`);
  lines.push('');
  lines.push(`## Per-axis aggregates`);
  lines.push('');
  lines.push(`| Axis | Rate | Notes |`);
  lines.push(`|---|---|---|`);
  lines.push(`| urlPresent     | ${pct(urlPresent, n)} | response contained ≥1 parseable \`justsearch://\` URL |`);
  lines.push(`| schemeValid    | ${pct(schemeValid, n)} | first URL parses cleanly |`);
  lines.push(`| opIdResolves   | ${pct(opIdResolves, n)} | first URL's id ∈ catalog |`);
  lines.push(`| argsValidate   | ${pct(argsValid, argsScored.length)} | (excludes scenarios where catalog entry has no schema) |`);
  lines.push(`| semanticMatch  | ${pct(semanticMatch, semanticScored.length)} | (excludes scenarios without expectedURL/expectedURLs) |`);
  lines.push('');

  // Failure samples
  const failures = results.filter((r) => !r.pass);
  if (failures.length > 0) {
    lines.push(`## Failure samples (first 10)`);
    lines.push('');
    for (const f of failures.slice(0, 10)) {
      lines.push(`### ${f.id}`);
      lines.push(`- prompt:  \`${f.prompt}\``);
      lines.push(`- expected: ${f.expectedURL ? `\`${f.expectedURL}\`` : (f.expectedURLs ? `\`${f.expectedURLs.join(', ')}\`` : '(none)')}`);
      const emitted = (f.urlAxes?.extractedUrls || []).map((e) => e.raw).join(', ') || '(none extracted)';
      lines.push(`- emitted: \`${emitted}\``);
      lines.push(`- failures: ${(f.urlAxes?.failures || []).join(' / ') || '(none recorded)'}`);
      if (f.error) lines.push(`- transport error: ${f.error.message}`);
      lines.push('');
    }
  }

  process.stdout.write(lines.join('\n'));
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

const SUBCOMMANDS = {
  'render-prompt': cmdRenderPrompt,
  run: cmdRun,
  summarize: cmdSummarize,
};

function usage() {
  return `Usage: node scripts/ci/agent-battery-url-probe.mjs <subcommand> [options]

Subcommands:
  render-prompt  --api-base-url <url> [--out <path>]
  run            --api-base-url <url> --scenarios <path> --out <path> [--scenario-timeout-ms N]
  summarize      --results <path>
`;
}

async function main() {
  const argv = process.argv.slice(2);
  const subcommand = argv[0];
  if (!subcommand || !SUBCOMMANDS[subcommand]) {
    process.stderr.write(usage());
    process.exit(2);
  }
  const { values } = nodeParseArgs({
    args: argv.slice(1),
    options: {
      'api-base-url': { type: 'string' },
      'llama-server-url': { type: 'string' },
      'substrate-url': { type: 'string' },
      out: { type: 'string' },
      scenarios: { type: 'string' },
      results: { type: 'string' },
      'scenario-timeout-ms': { type: 'string' },
      'max-tokens': { type: 'string' },
    },
    strict: false,
    allowPositionals: true,
  });
  try {
    await SUBCOMMANDS[subcommand](values);
  } catch (err) {
    process.stderr.write(`error: ${err.message}\n`);
    process.exit(1);
  }
}

main();
