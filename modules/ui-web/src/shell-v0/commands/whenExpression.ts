// SPDX-License-Identifier: Apache-2.0
/**
 * whenExpression — Tempdoc 508 §11.1 / §13.1 — VS Code-grammar
 * `when` clause parser + evaluator.
 *
 * Grammar (matches VS Code's documented when-clause subset):
 *   expr  := or
 *   or    := and ('||' and)*
 *   and   := not ('&&' not)*
 *   not   := '!' atom | atom
 *   atom  := comparison | '(' expr ')' | key
 *   comparison := key OP rhs
 *   OP := '==' | '===' | '!=' | '!==' | '>' | '>=' | '<' | '<=' | '=~'
 *       | 'in' | 'not in'
 *   key  := flat-identifier (matches /[A-Za-z_$][\w$]*\/)
 *   rhs  := stringLiteral | numberLiteral | regexLiteral | bareWord
 *
 * Context shape is flat (§13.1 verdict): no dotted-path access. The
 * caller provides a `Record<string, unknown>` and the evaluator looks
 * up keys verbatim. A bare key (no operator) evaluates as a truthy
 * check. `in` / `not in` test value membership in a delimited string
 * (comma-separated).
 *
 * Policy on malformed expressions (§13.1):
 *   - Parse errors → silently-false + WARN-once per expression id.
 *   - Runtime errors (e.g., regex throws) → silently-false (no WARN
 *     because the cache hit means the expression itself parsed
 *     fine; a bad regex is the author's runtime mistake).
 *
 * Performance: parsed expressions are cached in a Map keyed by the
 * raw string. The cache is unbounded — registry sizes are bounded
 * (~hundreds at worst) so memory is fine. If plugin churn produces
 * many distinct expressions, switch to LRU; documented working
 * assumption.
 */

export type WhenExpression = string;

export interface WhenContext {
  readonly [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// AST
// ---------------------------------------------------------------------------

type Op =
  | '=='
  | '!='
  | '>'
  | '>='
  | '<'
  | '<='
  | '=~'
  | 'in'
  | 'notin';

type Rhs =
  | { kind: 'string'; value: string }
  | { kind: 'number'; value: number }
  | { kind: 'regex'; value: RegExp }
  | { kind: 'bare'; value: string };

type Node =
  | { kind: 'or'; left: Node; right: Node }
  | { kind: 'and'; left: Node; right: Node }
  | { kind: 'not'; inner: Node }
  | { kind: 'key'; key: string }
  | { kind: 'compare'; key: string; op: Op; rhs: Rhs }
  | { kind: 'membership'; needle: string; haystackKey: string; negate: boolean };

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

class ParseError extends Error {}

interface Lexer {
  pos: number;
  src: string;
}

function peek(l: Lexer): string {
  return l.pos < l.src.length ? l.src[l.pos]! : '';
}

function skipWhitespace(l: Lexer): void {
  while (l.pos < l.src.length && /\s/.test(l.src[l.pos]!)) l.pos++;
}

function consume(l: Lexer, s: string): boolean {
  if (l.src.slice(l.pos, l.pos + s.length) === s) {
    l.pos += s.length;
    return true;
  }
  return false;
}

function parseIdentifier(l: Lexer): string {
  skipWhitespace(l);
  let i = l.pos;
  if (!/[A-Za-z_$]/.test(l.src[i] ?? '')) {
    throw new ParseError(`expected identifier at position ${l.pos}`);
  }
  while (i < l.src.length && /[\w$.-]/.test(l.src[i]!)) i++;
  // Note: we allow `.` and `-` inside identifiers because surface
  // ids and operation ids in this codebase use them
  // (e.g., core.search-surface). This is more permissive than VS
  // Code's identifier grammar but matches our existing namespace
  // conventions. RHS comparisons against these ids work as
  // expected.
  const id = l.src.slice(l.pos, i);
  l.pos = i;
  return id;
}

function parseRhs(l: Lexer): Rhs {
  skipWhitespace(l);
  const c = peek(l);
  if (c === "'" || c === '"') {
    // String literal.
    const quote = c;
    l.pos++;
    let s = '';
    while (l.pos < l.src.length && l.src[l.pos] !== quote) {
      if (l.src[l.pos] === '\\' && l.pos + 1 < l.src.length) {
        s += l.src[l.pos + 1];
        l.pos += 2;
      } else {
        s += l.src[l.pos];
        l.pos++;
      }
    }
    if (l.src[l.pos] !== quote) throw new ParseError('unterminated string');
    l.pos++;
    return { kind: 'string', value: s };
  }
  if (c === '/') {
    // Regex literal.
    l.pos++;
    let s = '';
    while (l.pos < l.src.length && l.src[l.pos] !== '/') {
      const c1 = l.src[l.pos]!;
      if (c1 === '\\' && l.pos + 1 < l.src.length) {
        s += c1 + l.src[l.pos + 1]!;
        l.pos += 2;
      } else {
        s += c1;
        l.pos++;
      }
    }
    if (l.src[l.pos] !== '/') throw new ParseError('unterminated regex');
    l.pos++;
    let flags = '';
    while (l.pos < l.src.length && /[imsu]/.test(l.src[l.pos]!)) {
      flags += l.src[l.pos];
      l.pos++;
    }
    try {
      return { kind: 'regex', value: new RegExp(s, flags) };
    } catch {
      throw new ParseError(`invalid regex /${s}/${flags}`);
    }
  }
  if (/[0-9-]/.test(c) && (c !== '-' || /[0-9]/.test(l.src[l.pos + 1] ?? ''))) {
    // Number literal.
    let i = l.pos;
    if (l.src[i] === '-') i++;
    while (i < l.src.length && /[0-9.]/.test(l.src[i]!)) i++;
    const n = Number(l.src.slice(l.pos, i));
    l.pos = i;
    return { kind: 'number', value: n };
  }
  // Bareword — used for unquoted identifiers as RHS. Matches our
  // existing convention `selectionKind == search-hit`.
  let i = l.pos;
  while (i < l.src.length && /[\w$.\-]/.test(l.src[i]!)) i++;
  const bare = l.src.slice(l.pos, i);
  if (bare.length === 0) throw new ParseError(`expected rhs at position ${l.pos}`);
  l.pos = i;
  return { kind: 'bare', value: bare };
}

function parseAtom(l: Lexer): Node {
  skipWhitespace(l);
  if (consume(l, '(')) {
    const inner = parseExpr(l);
    skipWhitespace(l);
    if (!consume(l, ')')) throw new ParseError('expected )');
    return inner;
  }
  if (consume(l, '!')) {
    return { kind: 'not', inner: parseAtom(l) };
  }
  const key = parseIdentifier(l);
  skipWhitespace(l);
  // Look for an operator following the key.
  const rest = l.src.slice(l.pos);
  if (rest.startsWith('===')) {
    l.pos += 3;
    return { kind: 'compare', key, op: '==', rhs: parseRhs(l) };
  }
  if (rest.startsWith('!==')) {
    l.pos += 3;
    return { kind: 'compare', key, op: '!=', rhs: parseRhs(l) };
  }
  if (rest.startsWith('==')) {
    l.pos += 2;
    return { kind: 'compare', key, op: '==', rhs: parseRhs(l) };
  }
  if (rest.startsWith('!=')) {
    l.pos += 2;
    return { kind: 'compare', key, op: '!=', rhs: parseRhs(l) };
  }
  if (rest.startsWith('>=')) {
    l.pos += 2;
    return { kind: 'compare', key, op: '>=', rhs: parseRhs(l) };
  }
  if (rest.startsWith('<=')) {
    l.pos += 2;
    return { kind: 'compare', key, op: '<=', rhs: parseRhs(l) };
  }
  if (rest.startsWith('=~')) {
    l.pos += 2;
    return { kind: 'compare', key, op: '=~', rhs: parseRhs(l) };
  }
  if (rest.startsWith('>')) {
    l.pos += 1;
    return { kind: 'compare', key, op: '>', rhs: parseRhs(l) };
  }
  if (rest.startsWith('<')) {
    l.pos += 1;
    return { kind: 'compare', key, op: '<', rhs: parseRhs(l) };
  }
  // `not in` first (longer match). Membership: LHS is treated as a
  // literal needle (not a key lookup); RHS is the key whose value is
  // the comma-separated haystack. Reads as
  // `<value> in <containerKey>`, matching VS Code's documented usage.
  if (rest.startsWith('not ') && rest.slice(4).trimStart().startsWith('in ')) {
    l.pos += 4 + (rest.slice(4).length - rest.slice(4).trimStart().length) + 3;
    skipWhitespace(l);
    const haystackKey = parseIdentifier(l);
    return { kind: 'membership', needle: key, haystackKey, negate: true };
  }
  if (rest.startsWith('in ')) {
    l.pos += 3;
    skipWhitespace(l);
    const haystackKey = parseIdentifier(l);
    return { kind: 'membership', needle: key, haystackKey, negate: false };
  }
  // No operator — bare-key truthy check.
  return { kind: 'key', key };
}

function parseNot(l: Lexer): Node {
  skipWhitespace(l);
  if (consume(l, '!')) {
    return { kind: 'not', inner: parseAtom(l) };
  }
  return parseAtom(l);
}

function parseAnd(l: Lexer): Node {
  let left = parseNot(l);
  while (true) {
    skipWhitespace(l);
    if (!consume(l, '&&')) break;
    const right = parseNot(l);
    left = { kind: 'and', left, right };
  }
  return left;
}

function parseExpr(l: Lexer): Node {
  let left = parseAnd(l);
  while (true) {
    skipWhitespace(l);
    if (!consume(l, '||')) break;
    const right = parseAnd(l);
    left = { kind: 'or', left, right };
  }
  return left;
}

export function parseWhen(expr: WhenExpression): Node | null {
  const l: Lexer = { pos: 0, src: expr };
  try {
    const node = parseExpr(l);
    skipWhitespace(l);
    if (l.pos !== expr.length) {
      throw new ParseError(`trailing content at position ${l.pos}: ${expr.slice(l.pos)}`);
    }
    return node;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Evaluator
// ---------------------------------------------------------------------------

function truthy(v: unknown): boolean {
  if (v === undefined || v === null) return false;
  if (typeof v === 'boolean') return v;
  if (typeof v === 'number') return v !== 0;
  if (typeof v === 'string') return v.length > 0;
  return true;
}

function rhsValue(rhs: Rhs): unknown {
  if (rhs.kind === 'string') return rhs.value;
  if (rhs.kind === 'number') return rhs.value;
  if (rhs.kind === 'regex') return rhs.value;
  return rhs.value;
}

function evaluateNode(node: Node, ctx: WhenContext): boolean {
  switch (node.kind) {
    case 'or':
      return evaluateNode(node.left, ctx) || evaluateNode(node.right, ctx);
    case 'and':
      return evaluateNode(node.left, ctx) && evaluateNode(node.right, ctx);
    case 'not':
      return !evaluateNode(node.inner, ctx);
    case 'key':
      return truthy(ctx[node.key]);
    case 'compare': {
      const lhs = ctx[node.key];
      const rhs = rhsValue(node.rhs);
      switch (node.op) {
        case '==':
          // Coerce-string comparison so `selectionCount == 2` works
          // (rhs is a number, lhs is also a number in our ctx; if
          // strings get compared, that works too).
          return String(lhs) === String(rhs);
        case '!=':
          return String(lhs) !== String(rhs);
        case '>':
          return Number(lhs) > Number(rhs);
        case '>=':
          return Number(lhs) >= Number(rhs);
        case '<':
          return Number(lhs) < Number(rhs);
        case '<=':
          return Number(lhs) <= Number(rhs);
        case '=~':
          if (rhs instanceof RegExp) return rhs.test(String(lhs));
          return false;
        case 'in':
        case 'notin':
          // Unreachable: 'in' / 'notin' are now expressed as
          // { kind: 'membership' } (see parser). Preserved here for
          // type-completeness only.
          return false;
      }
    }
    case 'membership': {
      const hay = String(ctx[node.haystackKey] ?? '');
      const members = hay.split(',').map((s) => s.trim()).filter(Boolean);
      const has = members.includes(node.needle);
      return node.negate ? !has : has;
    }
  }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

const cache = new Map<string, Node | null>();
const warned = new Set<string>();

/**
 * Evaluate a when expression against a context. Returns true when no
 * expression was supplied (undefined / empty = always-visible).
 * Returns false on parse error after logging a single WARN per expr.
 */
export function evaluateWhen(expr: WhenExpression | undefined, ctx: WhenContext): boolean {
  if (expr === undefined || expr.trim().length === 0) return true;
  let node = cache.get(expr);
  if (node === undefined) {
    node = parseWhen(expr);
    cache.set(expr, node);
    if (node === null && !warned.has(expr)) {
      warned.add(expr);
      try {
        // eslint-disable-next-line no-console
        console.warn(`[when] failed to parse expression: ${expr}`);
      } catch {
        /* swallow */
      }
    }
  }
  if (node === null) return false;
  try {
    return evaluateNode(node, ctx);
  } catch {
    return false;
  }
}

export function __resetWhenCacheForTest(): void {
  cache.clear();
  warned.clear();
}
