/**
 * SARIF rule descriptions for the hook-integrity gate (tempdoc 592).
 * Every ruleId emitted by truth-table.mjs has an entry here.
 */
export const HOOK_INTEGRITY_RULE_DESCRIPTIONS = {
  'hook-integrity/manifest-missing':
    'The hook manifest governance/agent-hooks.v1.json was not found.',
  'hook-integrity/manifest-unreadable':
    'The hook manifest could not be parsed as JSON.',
  'hook-integrity/dangling-binding':
    'A binding references a hookId not present in manifest.hooks.',
  'hook-integrity/binding-resolves': 'A binding resolves to a catalog hook (pass).',
  'hook-integrity/orphan-catalog-hook':
    'A manifest.hooks entry is declared but never bound to any event.',
  'hook-integrity/catalog-bound': 'A catalog hook is bound (pass).',
  'hook-integrity/unwired-hook':
    'A manifest hook is absent from the live .claude/settings.local.json, so it never fires at runtime — ' +
    'the manifest agreeing with itself is not the same as the harness reading it. Copy the entry from ' +
    '.claude/settings.local.json.example, or declare "wiring": "opt-in" + "wiringNote" for a deliberate opt-out.',
  'hook-integrity/live-wiring': 'A catalog hook is present in the live settings, or is a declared opt-in (pass).',
  'hook-integrity/cwd-relative-command':
    'A hook command in .claude/settings.local.json is cwd-relative (not the ${CLAUDE_PROJECT_DIR} exec-form) — the tempdoc-592 crash class.',
  'hook-integrity/cwd-invariant-command': 'A hook command is cwd-invariant (pass).',
  'hook-integrity/hook-load-failure':
    'A catalog hook crashed on load — a benign spawn (`{}` stdin + kill-switch) exited non-zero before main(), i.e. a syntax error or an unresolved import.',
  'hook-integrity/hook-loads': 'A catalog hook loads — its import graph resolves (pass).',
  'hook-integrity/blocking-hook-no-bite':
    'A blocking hook produced no bite evidence: its command-signal fixture did not emit the block signal, or its referenced unit test is missing.',
  'hook-integrity/blocking-hook-bites': 'A blocking hook bites (pass).',
  'hook-integrity/blocking-hook-no-bite-spec':
    'A role:blocking hook declares no `bite` spec, so its rung-2 claim is unverified.',
  'hook-integrity/blocking-hook-has-bite-spec': 'A blocking hook declares a bite spec (pass).',
  'hook-integrity/orphan-hook-file':
    'A hook file exists on disk (scripts/agent-analytics/hooks/) but is absent from the manifest catalog, ' +
    'so it can never be wired or gate-checked — the ui-shot-cleanup.mjs defect (tempdoc 861 §3c).',
  'hook-integrity/hook-file-in-catalog': 'A hook file on disk is present in the manifest catalog (pass).',
};
