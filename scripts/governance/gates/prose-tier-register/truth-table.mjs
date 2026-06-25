/**
 * prose-tier-register truth table — tempdoc 530 §Meta-loop.
 * Conforms to scripts/governance/lib/truth-table-runner.mjs.
 */

const ALLOWED_TIERS = new Set(['prose-only', 'hook', 'hook-hint', 'archunit', 'lint', 'gate']);

/** @param {{rowId: string, tier: string}} input */
export function verdictForRowTier(input) {
  const { rowId, tier } = input;
  if (!ALLOWED_TIERS.has(tier)) {
    return {
      ruleId: 'prose-tier-register/unknown-tier',
      status: 'fail',
      reason: `row '${rowId}' has unknown tier '${tier}'; expected one of ${[...ALLOWED_TIERS].join(', ')}`,
    };
  }
  return { ruleId: 'prose-tier-register/within-register', status: 'pass', reason: `row '${rowId}' tier '${tier}' valid` };
}

/** @param {{rowId: string, gateId: string, knownGateIds: string[]}} input */
export function verdictForGateReference(input) {
  const { rowId, gateId, knownGateIds } = input;
  if (knownGateIds.includes(gateId)) {
    return {
      ruleId: 'prose-tier-register/within-register',
      status: 'pass',
      reason: `row '${rowId}' gate '${gateId}' resolves`,
    };
  }
  return {
    ruleId: 'prose-tier-register/dangling-gate-reference',
    status: 'fail',
    reason: `row '${rowId}' references gate '${gateId}' which is not in governance/registry.v1.json`,
  };
}

/** @param {{rowId: string, priorTier: string, liveTier: string, changeCovered: boolean}} input */
export function verdictForTierChange(input) {
  const { rowId, priorTier, liveTier, changeCovered } = input;
  if (priorTier === liveTier) {
    return { ruleId: 'prose-tier-register/within-register', status: 'pass', reason: `row '${rowId}' tier unchanged` };
  }
  if (changeCovered) {
    return {
      ruleId: 'prose-tier-register/declared-tier-change',
      status: 'info',
      reason: `row '${rowId}' tier '${priorTier}' → '${liveTier}'; classification covers`,
    };
  }
  return {
    ruleId: 'prose-tier-register/silent-tier-change',
    status: 'fail',
    reason: `row '${rowId}' tier '${priorTier}' → '${liveTier}' without declared changeset`,
  };
}

/** @param {{rowId: string, changeCovered: boolean}} input */
export function verdictForRowRemoval(input) {
  const { rowId, changeCovered } = input;
  if (changeCovered) {
    return {
      ruleId: 'prose-tier-register/declared-row-removal',
      status: 'info',
      reason: `row '${rowId}' removed; classification covers`,
    };
  }
  return {
    ruleId: 'prose-tier-register/silent-row-removal',
    status: 'fail',
    reason: `row '${rowId}' removed without declared changeset`,
  };
}

/**
 * Verdict for an anchor in a rule file cross-referenced with the register.
 *
 * Inputs:
 *   slug                  — the anchor's slug
 *   source                — the rule file the anchor lives in
 *   presentInRegister     — does the register have a row with this slug?
 *   isNewVsBaseline       — was the anchor absent at the baseline ref?
 *   changeCovered         — does the PR declare a covering changeset?
 *
 * Verdicts:
 *   - presentInRegister                                  → pass
 *   - !presentInRegister && isNewVsBaseline              → fail (new-untagged-rule)
 *   - !presentInRegister && !isNewVsBaseline             → warning (untagged-grandfather; should be tagged when authored, but not a build break)
 *   - !presentInRegister && changeCovered                → info (declared exception)
 *
 * @param {{slug: string, source: string, presentInRegister: boolean, isNewVsBaseline: boolean, changeCovered: boolean}} input
 */
export function verdictForRuleAnchor(input) {
  const { slug, source, presentInRegister, isNewVsBaseline, changeCovered } = input;
  if (presentInRegister) {
    return {
      ruleId: 'prose-tier-register/anchor-tagged',
      status: 'pass',
      reason: `anchor '${slug}' in ${source} is in register`,
    };
  }
  if (changeCovered) {
    return {
      ruleId: 'prose-tier-register/anchor-declared-exception',
      status: 'info',
      reason: `anchor '${slug}' in ${source} not in register; classification covers`,
    };
  }
  if (isNewVsBaseline) {
    return {
      ruleId: 'prose-tier-register/new-untagged-rule',
      status: 'fail',
      reason: `anchor '${slug}' added in ${source} this PR but not in register; add a Slug row`,
    };
  }
  return {
    ruleId: 'prose-tier-register/untagged-grandfathered',
    status: 'info',
    reason: `anchor '${slug}' in ${source} pre-existed without register row; consider adding`,
  };
}

/**
 * Verdict for a must/never/always sentence in a rule file. Sentences inside
 * an anchored section pass. Sentences outside any anchor (i.e., the file
 * structure has not been registered as a rule yet) fail when net-new and
 * grandfather when pre-existing.
 *
 * @param {{sentence: string, source: string, anchor: string|null, isNewVsBaseline: boolean, changeCovered: boolean}} input
 */
export function verdictForSentence(input) {
  const { sentence, source, anchor, isNewVsBaseline, changeCovered } = input;
  if (anchor) {
    return {
      ruleId: 'prose-tier-register/sentence-tagged',
      status: 'pass',
      reason: `'${truncate(sentence)}' covered by anchor '${anchor}' in ${source}`,
    };
  }
  if (changeCovered) {
    return {
      ruleId: 'prose-tier-register/sentence-declared-exception',
      status: 'info',
      reason: `unanchored sentence in ${source} covered by declared changeset`,
    };
  }
  if (isNewVsBaseline) {
    return {
      ruleId: 'prose-tier-register/untagged-sentence',
      status: 'fail',
      reason: `new sentence in ${source} containing must/never/always lands outside any anchor: '${truncate(sentence)}'`,
    };
  }
  return {
    ruleId: 'prose-tier-register/untagged-sentence-grandfathered',
    status: 'info',
    reason: `pre-existing sentence in ${source} containing must/never/always lacks an anchor: '${truncate(sentence)}'`,
  };
}

function truncate(s) {
  return s.length > 80 ? s.slice(0, 77) + '...' : s;
}

/**
 * Verdict for a hook:<filename> reference in a register row.
 *
 * @param {{rowId: string, token: string, hookFileExists: boolean}} input
 */
export function verdictForHookReference(input) {
  const { rowId, token, hookFileExists } = input;
  if (hookFileExists) {
    return {
      ruleId: 'prose-tier-register/within-register',
      status: 'pass',
      reason: `row '${rowId}' hook '${token}' resolves`,
    };
  }
  return {
    ruleId: 'prose-tier-register/hook-reference-unresolved',
    status: 'fail',
    reason: `row '${rowId}' references hook '${token}' which is not in scripts/agent-analytics/hooks/`,
  };
}

/**
 * Verdict for an archunit:<TestClassName> reference in a register row.
 *
 * @param {{rowId: string, token: string, testClassFound: boolean}} input
 */
export function verdictForArchunitReference(input) {
  const { rowId, token, testClassFound } = input;
  if (testClassFound) {
    return {
      ruleId: 'prose-tier-register/within-register',
      status: 'pass',
      reason: `row '${rowId}' archunit '${token}' resolves`,
    };
  }
  return {
    ruleId: 'prose-tier-register/archunit-reference-unresolved',
    status: 'fail',
    reason: `row '${rowId}' references archunit class '${token}' which is not found under modules/*/src/test/`,
  };
}

/**
 * Verdict for a register row whose tier is gate/hook/archunit but carries
 * no Resolves-to marker.
 *
 * @param {{rowId: string, tier: string, isNewVsBaseline: boolean}} input
 */
export function verdictForMissingResolvesTo(input) {
  const { rowId, tier, isNewVsBaseline } = input;
  if (isNewVsBaseline) {
    return {
      ruleId: 'prose-tier-register/missing-resolves-to',
      status: 'fail',
      reason: `row '${rowId}' tagged '${tier}' added this PR but has no Resolves-to marker (need \`${tier}:<token>\`)`,
    };
  }
  return {
    ruleId: 'prose-tier-register/missing-resolves-to-grandfathered',
    status: 'info',
    reason: `pre-existing row '${rowId}' tagged '${tier}' has no Resolves-to marker; add one`,
  };
}

/**
 * Verdict for a register slug that doesn't have any anchor in the rule files.
 *
 * @param {{slug: string, isNewVsBaseline: boolean, changeCovered: boolean}} input
 */
export function verdictForOrphanRegisterRow(input) {
  const { slug, isNewVsBaseline, changeCovered } = input;
  if (changeCovered) {
    return {
      ruleId: 'prose-tier-register/orphan-declared-exception',
      status: 'info',
      reason: `register slug '${slug}' has no rule-file anchor; classification covers`,
    };
  }
  if (isNewVsBaseline) {
    return {
      ruleId: 'prose-tier-register/orphan-register-row',
      status: 'fail',
      reason: `register slug '${slug}' added this PR but no rule file has its anchor`,
    };
  }
  return {
    ruleId: 'prose-tier-register/orphan-grandfathered',
    status: 'info',
    reason: `register slug '${slug}' is unanchored; pre-existing — author rule-file anchor`,
  };
}
