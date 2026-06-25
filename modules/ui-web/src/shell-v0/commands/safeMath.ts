// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 521 §16.2 — safe arithmetic expression evaluator.
 *
 * A tiny recursive-descent parser for the four arithmetic operators
 * + - * /, parentheses, and decimal numbers. Used by the
 * {calculator} ambient template binding so a user-supplied
 * expression can be evaluated WITHOUT going through `eval()` or the
 * `Function` constructor (UNTRUSTED-template hostile-input safety).
 *
 * Grammar (LL(1)):
 *
 *   expression := term (('+'|'-') term)*
 *   term       := factor (('*'|'/') factor)*
 *   factor     := number | '(' expression ')' | ('+'|'-') factor
 *   number     := digit+ ('.' digit+)?
 *
 * Errors throw a `SafeMathError` so callers can distinguish parse /
 * evaluation failures from generic Error throws further up the stack.
 */

export class SafeMathError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SafeMathError';
  }
}

type Token =
  | { kind: 'number'; value: number }
  | { kind: 'op'; value: '+' | '-' | '*' | '/' }
  | { kind: 'lparen' }
  | { kind: 'rparen' };

function tokenize(src: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < src.length) {
    const ch = src[i]!;
    if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
      i++;
      continue;
    }
    if (ch === '+' || ch === '-' || ch === '*' || ch === '/') {
      tokens.push({ kind: 'op', value: ch });
      i++;
      continue;
    }
    if (ch === '(') {
      tokens.push({ kind: 'lparen' });
      i++;
      continue;
    }
    if (ch === ')') {
      tokens.push({ kind: 'rparen' });
      i++;
      continue;
    }
    if ((ch >= '0' && ch <= '9') || ch === '.') {
      let j = i;
      let seenDot = false;
      while (j < src.length) {
        const c = src[j]!;
        if (c >= '0' && c <= '9') {
          j++;
        } else if (c === '.' && !seenDot) {
          seenDot = true;
          j++;
        } else {
          break;
        }
      }
      const slice = src.slice(i, j);
      const n = Number.parseFloat(slice);
      if (!Number.isFinite(n)) {
        throw new SafeMathError(`Invalid number literal: ${slice}`);
      }
      tokens.push({ kind: 'number', value: n });
      i = j;
      continue;
    }
    throw new SafeMathError(`Unexpected character '${ch}' at position ${i}`);
  }
  return tokens;
}

class Parser {
  private pos = 0;
  constructor(private readonly tokens: Token[]) {}

  parseExpression(): number {
    let left = this.parseTerm();
    while (this.pos < this.tokens.length) {
      const t = this.tokens[this.pos]!;
      if (t.kind !== 'op' || (t.value !== '+' && t.value !== '-')) break;
      this.pos++;
      const right = this.parseTerm();
      left = t.value === '+' ? left + right : left - right;
    }
    return left;
  }

  private parseTerm(): number {
    let left = this.parseFactor();
    while (this.pos < this.tokens.length) {
      const t = this.tokens[this.pos]!;
      if (t.kind !== 'op' || (t.value !== '*' && t.value !== '/')) break;
      this.pos++;
      const right = this.parseFactor();
      if (t.value === '/') {
        if (right === 0) {
          throw new SafeMathError('Division by zero');
        }
        left = left / right;
      } else {
        left = left * right;
      }
    }
    return left;
  }

  private parseFactor(): number {
    if (this.pos >= this.tokens.length) {
      throw new SafeMathError('Unexpected end of expression');
    }
    const t = this.tokens[this.pos]!;
    if (t.kind === 'op' && (t.value === '+' || t.value === '-')) {
      this.pos++;
      const inner = this.parseFactor();
      return t.value === '-' ? -inner : inner;
    }
    if (t.kind === 'number') {
      this.pos++;
      return t.value;
    }
    if (t.kind === 'lparen') {
      this.pos++;
      const inner = this.parseExpression();
      const close = this.tokens[this.pos];
      if (!close || close.kind !== 'rparen') {
        throw new SafeMathError("Missing closing ')'");
      }
      this.pos++;
      return inner;
    }
    throw new SafeMathError(`Unexpected token at position ${this.pos}`);
  }

  done(): boolean {
    return this.pos >= this.tokens.length;
  }
}

/**
 * Evaluate `expression` against the safe-math grammar. Returns the
 * computed numeric value. Throws {@link SafeMathError} on any parse
 * error, on division by zero, or on a non-finite result.
 */
export function evaluateSafeMath(expression: string): number {
  if (typeof expression !== 'string') {
    throw new SafeMathError('Expression must be a string');
  }
  const trimmed = expression.trim();
  if (trimmed.length === 0) {
    throw new SafeMathError('Expression is empty');
  }
  const tokens = tokenize(trimmed);
  if (tokens.length === 0) {
    throw new SafeMathError('Expression is empty after tokenization');
  }
  const parser = new Parser(tokens);
  const result = parser.parseExpression();
  if (!parser.done()) {
    throw new SafeMathError('Unconsumed trailing tokens in expression');
  }
  if (!Number.isFinite(result)) {
    throw new SafeMathError('Result is not a finite number');
  }
  return result;
}

/** Format the result with up to 12 significant digits, trimming trailing zeros. */
export function formatSafeMathResult(value: number): string {
  if (Number.isInteger(value)) return String(value);
  const s = value.toPrecision(12);
  return s.replace(/\.?0+$/, '');
}
