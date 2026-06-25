// SPDX-License-Identifier: Apache-2.0
/**
 * 569 — the surface-composition DSL binding language (Move 2 / the DSL tier).
 *
 * A non-Turing-complete, side-effect-free, guaranteed-terminating expression language
 * (CEL/AEL-class) over a surface's typed data context. Authored as DATA (e.g. a region's
 * `visibleWhen` predicate), never code — no closures, no `eval`. Grammar:
 *
 *   or      := and ('||' and)*
 *   and     := not ('&&' not)*
 *   not     := '!' not | cmp
 *   cmp     := primary (('=='|'!='|'<'|'>'|'<='|'>=') primary)?
 *   primary := number | string | 'true' | 'false' | 'null' | path | '(' or ')'
 *   path    := ident ('.' ident)*
 *
 * Resolution is read-only member access on the context; an unknown path resolves to
 * undefined. There are NO function calls, loops, or assignments — so evaluation terminates
 * and cannot mutate or exfiltrate. This is the only place an authored binding is interpreted.
 */

type TokType = 'num' | 'str' | 'ident' | 'op' | 'lparen' | 'rparen';
interface Tok {
  readonly t: TokType;
  readonly v: string;
}

const OPS = ['==', '!=', '<=', '>=', '&&', '||', '<', '>', '!'];

function tokenize(src: string): Tok[] {
  const toks: Tok[] = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i] as string;
    if (/\s/.test(c)) {
      i++;
      continue;
    }
    if (c === '(') {
      toks.push({ t: 'lparen', v: c });
      i++;
      continue;
    }
    if (c === ')') {
      toks.push({ t: 'rparen', v: c });
      i++;
      continue;
    }
    if (c === '"' || c === "'") {
      let j = i + 1;
      let s = '';
      while (j < src.length && src[j] !== c) {
        s += src[j];
        j++;
      }
      if (j >= src.length) throw new Error('unterminated string');
      toks.push({ t: 'str', v: s });
      i = j + 1;
      continue;
    }
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < src.length && /[0-9.]/.test(src[j] as string)) j++;
      toks.push({ t: 'num', v: src.slice(i, j) });
      i = j;
      continue;
    }
    if (/[a-zA-Z_]/.test(c)) {
      let j = i;
      while (j < src.length && /[a-zA-Z0-9_.]/.test(src[j] as string)) j++;
      toks.push({ t: 'ident', v: src.slice(i, j) });
      i = j;
      continue;
    }
    const op = OPS.find((o) => src.startsWith(o, i));
    if (!op) throw new Error(`unexpected character '${c}'`);
    toks.push({ t: 'op', v: op });
    i += op.length;
  }
  return toks;
}

function truthy(v: unknown): boolean {
  return v !== false && v !== null && v !== undefined && v !== 0 && v !== '';
}

function compare(op: string, l: unknown, r: unknown): boolean {
  switch (op) {
    case '==':
      return l === r;
    case '!=':
      return l !== r;
    case '<':
      return (l as number) < (r as number);
    case '>':
      return (l as number) > (r as number);
    case '<=':
      return (l as number) <= (r as number);
    case '>=':
      return (l as number) >= (r as number);
    default:
      return false;
  }
}

function resolvePath(path: string, ctx: Record<string, unknown>): unknown {
  let cur: unknown = ctx;
  for (const seg of path.split('.')) {
    if (cur === null || typeof cur !== 'object') return undefined;
    cur = (cur as Record<string, unknown>)[seg];
  }
  return cur;
}

class Parser {
  private i = 0;
  constructor(
    private readonly toks: Tok[],
    private readonly ctx: Record<string, unknown>,
  ) {}

  private peek(): Tok | undefined {
    return this.toks[this.i];
  }
  private next(): Tok | undefined {
    return this.toks[this.i++];
  }

  parse(): unknown {
    const v = this.parseOr();
    if (this.i < this.toks.length) throw new Error('trailing tokens');
    return v;
  }

  private parseOr(): unknown {
    let v = this.parseAnd();
    while (this.peek()?.v === '||') {
      this.next();
      const r = this.parseAnd();
      v = truthy(v) || truthy(r);
    }
    return v;
  }

  private parseAnd(): unknown {
    let v = this.parseNot();
    while (this.peek()?.v === '&&') {
      this.next();
      const r = this.parseNot();
      v = truthy(v) && truthy(r);
    }
    return v;
  }

  private parseNot(): unknown {
    if (this.peek()?.v === '!') {
      this.next();
      return !truthy(this.parseNot());
    }
    return this.parseCmp();
  }

  private parseCmp(): unknown {
    const l = this.parsePrimary();
    const op = this.peek();
    if (op?.t === 'op' && ['==', '!=', '<', '>', '<=', '>='].includes(op.v)) {
      this.next();
      const r = this.parsePrimary();
      return compare(op.v, l, r);
    }
    return l;
  }

  private parsePrimary(): unknown {
    const t = this.next();
    if (!t) throw new Error('unexpected end of expression');
    if (t.t === 'lparen') {
      const v = this.parseOr();
      const close = this.next();
      if (close?.t !== 'rparen') throw new Error('expected )');
      return v;
    }
    if (t.t === 'num') return Number(t.v);
    if (t.t === 'str') return t.v;
    if (t.t === 'ident') {
      if (t.v === 'true') return true;
      if (t.v === 'false') return false;
      if (t.v === 'null') return null;
      return resolvePath(t.v, this.ctx);
    }
    throw new Error(`unexpected token '${t.v}'`);
  }
}

/**
 * Evaluate a binding expression to a boolean against a data context. A malformed expression
 * resolves to `false` (fail-closed for visibility) — this try/catch is the validation
 * boundary for untrusted authored expressions, not a swallowed bug.
 */
export function evaluateBinding(expr: string, ctx: Record<string, unknown>): boolean {
  try {
    return truthy(new Parser(tokenize(expr), ctx).parse());
  } catch {
    return false;
  }
}
