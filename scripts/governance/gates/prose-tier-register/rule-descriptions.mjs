/**
 * SARIF rule descriptions for the prose-tier-register gate (tempdoc 530 §Meta-loop).
 */

export const PROSE_TIER_RULE_DESCRIPTIONS = {
  'prose-tier-register/register-missing':
    'Tier register file not found at .claude/rules/tier-register.md',
  'prose-tier-register/malformed-row':
    'A register row could not be parsed (missing required cell)',
  'prose-tier-register/unknown-tier':
    "A row's tier value is not one of {prose-only, hook, archunit, lint, gate}",
  'prose-tier-register/dangling-gate-reference':
    "A row tagged `gate` references a gate id that does not exist in governance/registry.v1.json",
  'prose-tier-register/silent-tier-change':
    'A row\'s tier changed in this PR without a declared tier-change changeset',
  'prose-tier-register/declared-tier-change':
    "A row's tier changed; classification covers it",
  'prose-tier-register/silent-row-removal':
    'A register row was removed in this PR without a declared rule-retired changeset',
  'prose-tier-register/declared-row-removal':
    'A register row was removed; classification covers it',
  'prose-tier-register/within-register': 'Register internal state is consistent',
  'prose-tier-register/anchor-tagged':
    'A rule-file anchor has a corresponding row in the register',
  'prose-tier-register/new-untagged-rule':
    'A new <!-- rule:<slug> --> anchor landed in this PR without a register row (tempdoc 530 §Meta-loop headline closure)',
  'prose-tier-register/untagged-grandfathered':
    'A pre-existing rule-file anchor has no register row (informational; tagging recommended)',
  'prose-tier-register/anchor-declared-exception':
    'A rule-file anchor without a register row is covered by a declared changeset',
  'prose-tier-register/orphan-register-row':
    'A register row was added this PR but no rule file carries its anchor',
  'prose-tier-register/orphan-grandfathered':
    'A pre-existing register row has no rule-file anchor (informational)',
  'prose-tier-register/orphan-declared-exception':
    'An orphan register row is covered by a declared changeset',
  'prose-tier-register/sentence-tagged':
    'A must/never/always sentence falls inside an anchored section',
  'prose-tier-register/untagged-sentence':
    'A new must/never/always sentence landed in this PR outside any anchored section (tempdoc 530 §Meta-loop literal closure)',
  'prose-tier-register/untagged-sentence-grandfathered':
    'A pre-existing must/never/always sentence lacks an enclosing anchor (informational)',
  'prose-tier-register/sentence-declared-exception':
    'An unanchored must/never/always sentence is covered by a declared changeset',
  'prose-tier-register/hook-reference-unresolved':
    'A hook:<filename> marker does not resolve to a file under scripts/agent-analytics/hooks/',
  'prose-tier-register/archunit-reference-unresolved':
    'An archunit:<class> marker does not resolve to a test class under modules/*/src/test/',
  'prose-tier-register/missing-resolves-to':
    'A new row tagged gate/hook/archunit landed in this PR without a Resolves-to marker',
  'prose-tier-register/missing-resolves-to-grandfathered':
    'A pre-existing row tagged gate/hook/archunit has no Resolves-to marker (informational)',
};
