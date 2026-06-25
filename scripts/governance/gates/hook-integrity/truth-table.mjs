/**
 * hook-integrity truth-table — tempdoc 592 (rung 2).
 *
 * Pure verdict functions of the shape (input) -> { ruleId, status, reason }.
 * The enforcer does the impure measurement (parse manifest/settings, spawn
 * `node --check`, run bite subprocesses) and dispatches each measurement here.
 *
 * The invariant: the `hook` enforcement tier (claimed ~100% in the tier-register)
 * is only real if every wired hook is REACHABLE (cwd-invariant command + the file
 * loads) and every BLOCKING hook BITES (emits its block signal on a violating
 * input). "The file exists" — all the prose-tier-register gate could check — is
 * three properties short of "the hook enforces". This table closes the gap.
 */

const ID = 'hook-integrity';

/** A binding references a hookId that resolves to a manifest catalog entry. */
export function verdictForBinding({ event, hookId, resolved }) {
  if (!resolved) {
    return {
      ruleId: `${ID}/dangling-binding`,
      status: 'fail',
      reason: `binding under '${event}' references hookId '${hookId}' which is not in manifest.hooks`,
    };
  }
  return { ruleId: `${ID}/binding-resolves`, status: 'pass', reason: `binding '${hookId}' resolves` };
}

/** Every catalog hook is wired (bound) at least once — no orphan declaration. */
export function verdictForCatalogBound({ hookId, bound }) {
  if (!bound) {
    return {
      ruleId: `${ID}/orphan-catalog-hook`,
      status: 'fail',
      reason: `manifest.hooks['${hookId}'] is declared but never bound to any event`,
    };
  }
  return { ruleId: `${ID}/catalog-bound`, status: 'pass', reason: `'${hookId}' is bound` };
}

/**
 * Every command in the live settings hooks block is the cwd-invariant exec-form
 * (command:"node", args:["${CLAUDE_PROJECT_DIR}/..."]). The regression teeth for
 * the original 592 bug: a relative `node scripts/...` command is a build failure.
 */
export function verdictForCwdInvariantCommand({ location, isExecForm, commandRepr }) {
  if (!isExecForm) {
    return {
      ruleId: `${ID}/cwd-relative-command`,
      status: 'fail',
      reason:
        `hook command at ${location} is not cwd-invariant: ${commandRepr}. ` +
        `Use the exec-form {command:"node", args:["\${CLAUDE_PROJECT_DIR}/..."]} (regenerate from the manifest).`,
    };
  }
  return { ruleId: `${ID}/cwd-invariant-command`, status: 'pass', reason: `${location} is cwd-invariant` };
}

/** Each catalog hook's import graph resolves (benign spawn) — catches crash-on-load before it ships. */
export function verdictForLoad({ hookId, loaded, detail }) {
  if (!loaded) {
    return {
      ruleId: `${ID}/hook-load-failure`,
      status: 'fail',
      reason: `hook '${hookId}' crashed on load (imports must resolve): ${detail}`,
    };
  }
  return { ruleId: `${ID}/hook-loads`, status: 'pass', reason: `'${hookId}' loads` };
}

/**
 * Every BLOCKING hook has bite evidence: a command-signal bite emitted its block
 * signal on the violating fixture, or a referenced unit test exists. This is the
 * rung-2 "guarded & biting" property — the difference between "a guard exists"
 * and "the guard would catch the violation" (the repo's audit-without-test lesson).
 */
export function verdictForBite({ hookId, satisfied, kind, detail }) {
  if (!satisfied) {
    return {
      ruleId: `${ID}/blocking-hook-no-bite`,
      status: 'fail',
      reason: `blocking hook '${hookId}' has no bite evidence (${kind}): ${detail}`,
    };
  }
  return { ruleId: `${ID}/blocking-hook-bites`, status: 'pass', reason: `'${hookId}' bites (${kind})` };
}

/** A blocking hook must declare a `bite` spec at all (else its rung-2 claim is unverified). */
export function verdictForBiteDeclared({ hookId, hasBite }) {
  if (!hasBite) {
    return {
      ruleId: `${ID}/blocking-hook-no-bite-spec`,
      status: 'fail',
      reason: `manifest.hooks['${hookId}'] is role:blocking but declares no 'bite' spec`,
    };
  }
  return { ruleId: `${ID}/blocking-hook-has-bite-spec`, status: 'pass', reason: `'${hookId}' declares a bite` };
}

/** Every `hook:` marker in the tier-register resolves to a manifest catalog entry. */
export function verdictForTierRegisterSync({ marker, resolved }) {
  if (!resolved) {
    return {
      ruleId: `${ID}/tier-register-hook-unresolved`,
      status: 'fail',
      reason: `tier-register marker 'hook:${marker}' does not resolve to a manifest.hooks entry`,
    };
  }
  return { ruleId: `${ID}/tier-register-hook-resolves`, status: 'pass', reason: `hook:${marker} resolves` };
}
