// SPDX-License-Identifier: Apache-2.0

/**
 * Tempdoc 859 §D §2.3 / §3.3 T12 + T13 — the EFFORT rung, where one control governs two quantities.
 *
 * The rung means how long an ASK's answer is, and how much room a DELEGATED run gets. Those are
 * different things, and the obvious design — switch the copy by tier — is unshippable, because
 * `Sv3Composer` dispatches `delegate` on Ctrl/Cmd+Enter regardless of the tier control (852 kept the
 * accelerator deliberately). A reader in ask mode, looking at ask copy, can delegate. So the copy
 * names BOTH quantities and is true in both modes, and this file pins that it stays that way.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import {
  isSv3Effort,
  SV3_EFFORT_DEFAULT,
  SV3_EFFORT_OPTIONS,
  sv3EffortParams,
  type Sv3Effort,
} from './sv3-ask.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..', '..', '..', '..', '..');

describe('T12 — one description set, naming BOTH quantities (859 §D §2.3)', () => {
  /** Words that can only be about a DELEGATED run's room to work. */
  const RUN_WORDS = /\bsteps?\b/i;
  /** Words that can only be about the ANSWER the reader will read. */
  const ANSWER_WORDS = /\banswers?\b|\bsettings\b/i;

  it('every rung says what it does to the answer AND what it does to a delegated run', () => {
    // The failure this refuses: copy that describes one quantity while the chord is about to make
    // the other one active. Neither meaning is reliably "the active one" at read time, so naming
    // both is the honest form of 859 §4's requirement rather than a weaker version of it.
    for (const option of SV3_EFFORT_OPTIONS) {
      expect(option.description, `${option.id} names the run quantity`).toMatch(RUN_WORDS);
      expect(option.description, `${option.id} names the answer quantity`).toMatch(ANSWER_WORDS);
    }
  });

  it('does NOT word Quick as a limiter — no rung reduces a delegated run’s old allowance', () => {
    // 859 §D §2.2: `2 * n_ctx > n_ctx - 256` for every n_ctx, so there is no model and no rung under
    // which this design gives a run LESS room. Quick is the smallest raise; the leash is between
    // rungs, not against today's behaviour. Copy that said "limits" or "caps" would be false.
    const quick = SV3_EFFORT_OPTIONS.find((o) => o.id === 'quick')!;
    expect(quick.description.toLowerCase()).not.toMatch(/\blimit|\bcap|\brestrict|\bonly\b/);
  });

  it('keeps the copy in ONE set — no tier-keyed variant may reappear', () => {
    // The rev-1 design proposed `sv3EffortOptions(tier)`. It is unshippable (the chord), and a
    // reintroduction would be silent: both variants would render fine, they would just disagree
    // with what the send actually did.
    const source = readFileSync(join(HERE, 'sv3-ask.ts'), 'utf8');
    expect(source).not.toMatch(/sv3EffortOptions\s*\(/);
    expect(SV3_EFFORT_OPTIONS.filter((o) => o.isDefault)).toHaveLength(1);
  });

  it('leaves the ASK mapping untouched — the delegate quantity travels as the rung NAME', () => {
    // `sv3EffortParams` stays the ask-side authority. The delegate side deliberately sends no
    // parameters at all: only the backend can see `n_ctx`, so it owns the sizing.
    expect(sv3EffortParams('quick')).toMatchObject({ enableThinking: false });
    expect(sv3EffortParams('thorough')).toMatchObject({ enableThinking: true });
    expect(sv3EffortParams('standard')).toEqual({});
  });
});

describe('T13 — the rung literals are pinned to the Java mapping (859 §D §3.3)', () => {
  /**
   * WHY THIS TEST IS THE ONLY GUARD. The rung crosses the wire as a bare string. No register covers
   * the FE union ↔ Java mapping pair, and the backend's fallback-to-Standard is DELIBERATE (a caller
   * that names no rung must still get a working budget). Put together, a typo'd or renamed rung
   * degrades SILENTLY: every run keeps working, at the wrong size, with nothing anywhere saying so.
   *
   * It reads the Java source rather than calling it, because there is no JVM here. That is a weaker
   * check than a shared schema would be — it is chosen because the alternative is no check at all.
   */
  const POLICY = join(
    REPO,
    'modules/app-agent/src/main/java/io/justsearch/agent/AgentBudgetPolicy.java',
  );

  it('every FE rung is a token the Java policy recognises', () => {
    const java = readFileSync(POLICY, 'utf8');
    // The two non-default rungs are matched by literal in `AgentBudgetPolicy.multiplier`.
    for (const rung of ['quick', 'thorough'] satisfies Sv3Effort[]) {
      expect(java, `Java must recognise the '${rung}' rung`).toMatch(
        new RegExp(`String ${rung.toUpperCase()} = "${rung}"`),
      );
    }
    // `standard` is the DEFAULT arm rather than a literal — that is the deliberate fallback, and it
    // is why a renamed rung cannot fail loudly on its own.
    expect(SV3_EFFORT_DEFAULT).toBe('standard');
    expect(java).toMatch(/return STANDARD_MULTIPLIER;/);
  });

  it('the FE union has exactly the three rungs the backend maps, and no fourth', () => {
    // A fourth FE rung would silently resolve to Standard on the backend; a fourth Java rung would
    // be unreachable. Either way the two sides would have stopped describing the same control.
    expect(SV3_EFFORT_OPTIONS.map((o) => o.id)).toEqual(['quick', 'standard', 'thorough']);
    for (const rung of ['quick', 'standard', 'thorough']) expect(isSv3Effort(rung)).toBe(true);
    expect(isSv3Effort('exhaustive')).toBe(false);
    expect(isSv3Effort('QUICK')).toBe(false);
  });

  it('the Java policy declares one multiplier per rung, plus the background pin', () => {
    const java = readFileSync(POLICY, 'utf8');
    for (const name of [
      'QUICK_MULTIPLIER',
      'STANDARD_MULTIPLIER',
      'THOROUGH_MULTIPLIER',
      // 859 §D §2.9 — a background run bypasses both gates, so it is pinned at one window. If this
      // constant disappears, unsupervised runs quietly inherit the foreground multipliers.
      'BACKGROUND_MULTIPLIER',
    ]) {
      expect(java, `${name} must exist`).toMatch(new RegExp(`int ${name} = \\d+;`));
    }
  });
});
