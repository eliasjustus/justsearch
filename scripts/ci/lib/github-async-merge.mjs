import { createHash } from 'node:crypto';

export const ASYNC_MERGE_API_VERSION = '2026-03-10';
export const MERGE_METHOD = 'squash';
export const MERGE_ACTION = 'merge_queue';

const TERMINAL_STATES = new Set(['enqueued', 'merged', 'failed']);
const RESULT_STATES = new Set(['pending', ...TERMINAL_STATES]);
const FULL_SHA_RE = /^[0-9a-f]{40}$/i;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export class AsyncMergeProtocolError extends Error {
  constructor(message, diagnostic = null) {
    super(message);
    this.name = 'AsyncMergeProtocolError';
    this.diagnostic = diagnostic;
  }
}

export function sha256Utf8(value) {
  return createHash('sha256').update(Buffer.from(String(value ?? ''), 'utf8')).digest('hex');
}

export function buildAsyncMergePayload({ subject, body, headSha }) {
  if (!subject) throw new Error('subject is required.');
  if (!FULL_SHA_RE.test(String(headSha ?? ''))) throw new Error('headSha must be a 40-character hexadecimal object ID.');
  return {
    commit_title: subject,
    commit_message: String(body ?? ''),
    sha: headSha,
    merge_method: MERGE_METHOD,
    merge_action: MERGE_ACTION,
  };
}

export function parseGhIncludedResponse(raw) {
  const text = String(raw ?? '');
  const starts = [];
  const re = /(?:^|\n)(HTTP\/(?:1\.[01]|2(?:\.0)?)\s+\d{3}[^\r\n]*)(?:\r?\n)/g;
  for (const match of text.matchAll(re)) {
    starts.push(match.index + (match[0].startsWith('\n') ? 1 : 0));
  }
  if (!starts.length) throw new AsyncMergeProtocolError('gh response did not contain an HTTP status line.');
  const response = text.slice(starts.at(-1));
  const separator = /\r?\n\r?\n/.exec(response);
  if (!separator) throw new AsyncMergeProtocolError('gh response did not contain a header/body separator.');
  const headerText = response.slice(0, separator.index);
  const body = response.slice(separator.index + separator[0].length).trimEnd();
  const headerLines = headerText.split(/\r?\n/);
  const statusMatch = /^HTTP\/(?:1\.[01]|2(?:\.0)?)\s+(\d{3})(?:\s+(.*))?$/.exec(headerLines.shift() ?? '');
  if (!statusMatch) throw new AsyncMergeProtocolError('gh response status line was malformed.');
  const headers = {};
  for (const line of headerLines) {
    const colon = line.indexOf(':');
    if (colon <= 0) throw new AsyncMergeProtocolError(`gh response contained a malformed header: ${JSON.stringify(line)}.`);
    const key = line.slice(0, colon).trim().toLowerCase();
    const value = line.slice(colon + 1).trim();
    (headers[key] ??= []).push(value);
  }
  let json = null;
  if (body) {
    try {
      json = JSON.parse(body);
    } catch {
      throw new AsyncMergeProtocolError('gh response body was not valid JSON.', { statusCode: Number(statusMatch[1]), body });
    }
  }
  return {
    statusCode: Number(statusMatch[1]),
    reason: statusMatch[2] || '',
    headers,
    body,
    json,
  };
}

export function responseDiagnostic(response) {
  const details = response?.json?.details ?? {};
  return {
    httpStatus: response?.statusCode ?? null,
    state: response?.json?.status ?? null,
    message: details.message ?? response?.json?.message ?? null,
    uuid: details.uuid ?? null,
    mergeMethod: details.merge_method ?? null,
    mergeAction: details.merge_action ?? null,
    expectedHeadSha: details.expected_head_sha ?? null,
    mergeCommitSha: details.sha ?? null,
  };
}

function requireReceiptMetadata(response, expected) {
  const details = response?.json?.details ?? {};
  const diagnostic = responseDiagnostic(response);
  if (!details.uuid || typeof details.uuid !== 'string') {
    throw new AsyncMergeProtocolError('Asynchronous merge response omitted details.uuid.', diagnostic);
  }
  if (!UUID_RE.test(details.uuid)) {
    throw new AsyncMergeProtocolError(`Asynchronous merge response returned a malformed UUID: ${JSON.stringify(details.uuid)}.`, diagnostic);
  }
  if (expected.uuid && details.uuid !== expected.uuid) {
    throw new AsyncMergeProtocolError(`Asynchronous merge UUID mismatch: ${JSON.stringify(details.uuid)}.`, diagnostic);
  }
  if (details.merge_method !== expected.mergeMethod) {
    throw new AsyncMergeProtocolError(`Asynchronous merge method mismatch: ${JSON.stringify(details.merge_method)}.`, diagnostic);
  }
  if (details.merge_action !== expected.mergeAction) {
    throw new AsyncMergeProtocolError(`Asynchronous merge action mismatch: ${JSON.stringify(details.merge_action)}.`, diagnostic);
  }
  if (details.expected_head_sha !== expected.headSha) {
    throw new AsyncMergeProtocolError(`Asynchronous merge head mismatch: ${JSON.stringify(details.expected_head_sha)}.`, diagnostic);
  }
  return details.uuid;
}

function validateOptionalMetadata(response, expected) {
  const details = response?.json?.details ?? {};
  const diagnostic = responseDiagnostic(response);
  const checks = [
    ['uuid', expected.uuid, 'UUID'],
    ['merge_method', expected.mergeMethod, 'method'],
    ['merge_action', expected.mergeAction, 'action'],
    ['expected_head_sha', expected.headSha, 'head'],
  ];
  for (const [key, expectedValue, label] of checks) {
    if (details[key] != null && expectedValue != null && details[key] !== expectedValue) {
      throw new AsyncMergeProtocolError(`Asynchronous merge ${label} mismatch: ${JSON.stringify(details[key])}.`, diagnostic);
    }
  }
}

export function requireFreshPendingReceipt(response, expected) {
  const diagnostic = responseDiagnostic(response);
  if (response.statusCode !== 202) {
    throw new AsyncMergeProtocolError(`Expected a fresh 202 asynchronous merge receipt; received ${response.statusCode}.`, diagnostic);
  }
  if (response?.json?.status !== 'pending') {
    throw new AsyncMergeProtocolError(`Expected pending state on fresh receipt; received ${JSON.stringify(response?.json?.status)}.`, diagnostic);
  }
  const uuid = requireReceiptMetadata(response, expected);
  return { state: 'pending', uuid, diagnostic };
}

export function classifyAsyncResult(response, expected) {
  const diagnostic = responseDiagnostic(response);
  if (response.statusCode !== 200) {
    throw new AsyncMergeProtocolError(`Expected HTTP 200 while polling; received ${response.statusCode}.`, diagnostic);
  }
  const state = response?.json?.status;
  if (!RESULT_STATES.has(state)) {
    throw new AsyncMergeProtocolError(`Unknown asynchronous merge state: ${JSON.stringify(state)}.`, diagnostic);
  }
  if (state === 'pending') requireReceiptMetadata(response, expected);
  else validateOptionalMetadata(response, expected);
  return { state, diagnostic };
}

export async function pollAsyncMerge({
  fetchResult,
  expected,
  timeoutMs = 10 * 60_000,
  intervalMs = 5_000,
  now = () => Date.now(),
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
}) {
  if (typeof fetchResult !== 'function') throw new Error('fetchResult is required.');
  const startedAt = now();
  for (;;) {
    const elapsedBeforeFetch = now() - startedAt;
    if (elapsedBeforeFetch >= timeoutMs) {
      throw new AsyncMergeProtocolError(`Timed out after ${timeoutMs}ms waiting for the asynchronous merge request.`);
    }
    const result = classifyAsyncResult(await fetchResult({ remainingMs: timeoutMs - elapsedBeforeFetch }), expected);
    if (TERMINAL_STATES.has(result.state)) return result;
    const elapsedAfterFetch = now() - startedAt;
    if (elapsedAfterFetch >= timeoutMs) {
      throw new AsyncMergeProtocolError(`Timed out after ${timeoutMs}ms waiting for the asynchronous merge request.`, result.diagnostic);
    }
    await sleep(Math.min(intervalMs, timeoutMs - elapsedAfterFetch));
  }
}
