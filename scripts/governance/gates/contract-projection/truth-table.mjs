/**
 * Contract-projection truth table (tempdoc 564 facet 4e — the meta-substrate gate).
 *
 * Mechanizes the 564 thesis on the 530 kernel: every migrated wire record is the SINGLE generated
 * projection (record → JSON Schema → {TS, Zod}), its generated artifacts never drift from the
 * source, and no hand-authored second copy exists. Like execution-surface / prose-tier-register
 * this is a META-COORDINATOR: it does not re-implement the checks, it delegates to the two proven
 * gates (the regen drift check + the single-authority mandate check) and to a coverage check.
 *
 * Conforms to the kernel truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict: the generated TS/Zod match the codegen output (no drift, no hand-edit). */
export function verdictForSchemaTypesDrift({ ok, detail }) {
  if (ok) {
    return {
      ruleId: 'contract-projection/schema-types-fresh',
      status: 'pass',
      reason: 'Generated wire types + Zod schemas match the codegen output (no drift / hand-edit).',
    };
  }
  return {
    ruleId: 'contract-projection/schema-types-drift',
    status: 'fail',
    reason:
      'The generated wire types + Zod schemas drifted from the codegen output (a hand-edit, or an ' +
      'SSOT/schemas change not regenerated). Run `node scripts/codegen/gen-wire-schema-types.mjs` ' +
      'and commit. Detail: ' +
      String(detail || '').trim(),
  };
}

/** Verdict: no hand-authored copy of a migrated wire type exists outside the generated set. */
export function verdictForDuplicateWireType({ ok, detail }) {
  if (ok) {
    return {
      ruleId: 'contract-projection/single-authority',
      status: 'pass',
      reason: 'Every migrated wire type has exactly one (generated) authority — no hand copy.',
    };
  }
  return {
    ruleId: 'contract-projection/duplicate-wire-type',
    status: 'fail',
    reason:
      'A hand-authored copy of a migrated wire type exists outside the generated set — the second ' +
      'copy is unrepresentable by mandate (564 facet 4d). Delete it and import the generated type. ' +
      'Detail: ' +
      String(detail || '').trim(),
  };
}

/** Verdict: the contract-surfaces register and the codegen TARGETS declare the same record set. */
export function verdictForRegisterCoherence({ onlyInRegister, onlyInTargets }) {
  if ((onlyInRegister?.length ?? 0) === 0 && (onlyInTargets?.length ?? 0) === 0) {
    return {
      ruleId: 'contract-projection/register-coherent',
      status: 'pass',
      reason: 'The contract-surfaces register and the codegen TARGETS declare the same wire records.',
    };
  }
  const parts = [];
  if (onlyInRegister?.length) parts.push('in the register but not a codegen TARGET: ' + onlyInRegister.join(', '));
  if (onlyInTargets?.length) parts.push('a codegen TARGET but not registered: ' + onlyInTargets.join(', '));
  return {
    ruleId: 'contract-projection/register-drift',
    status: 'fail',
    reason:
      'governance/contract-surfaces.v1.json and the codegen TARGETS disagree on the wire-record set — ' +
      parts.join('; ') + '. Keep the register and TARGETS in lockstep.',
  };
}

/** Verdict: every declared consumer file exists and actually imports the record's generated module. */
export function verdictForDeclaredConsumers({ brokenConsumers }) {
  if (!brokenConsumers || brokenConsumers.length === 0) {
    return {
      ruleId: 'contract-projection/consumers-resolve',
      status: 'pass',
      reason: 'Every declared contract consumer exists and imports its generated wire module.',
    };
  }
  return {
    ruleId: 'contract-projection/consumer-broken',
    status: 'fail',
    reason:
      'These declared consumers in contract-surfaces.v1.json do not exist or no longer import the ' +
      'record’s generated module: ' + brokenConsumers.join(', ') + '. Fix the path or the import.',
  };
}

/** Verdict: every FE file importing a generated wire module is a declared consumer of that record. */
export function verdictForUndeclaredConsumers({ undeclared }) {
  if (!undeclared || undeclared.length === 0) {
    return {
      ruleId: 'contract-projection/consumers-declared',
      status: 'pass',
      reason: 'Every FE consumer of a generated wire module is declared in the contract-surfaces register.',
    };
  }
  return {
    ruleId: 'contract-projection/undeclared-consumer',
    status: 'fail',
    reason:
      'These FE files import a generated wire module but are not declared consumers in ' +
      'contract-surfaces.v1.json: ' + undeclared.join(', ') + '. Register each as a consumer ' +
      '(the FE-contract is a declared 530 surface — new parse-boundary readers must be registered).',
  };
}

/** Verdict: every codegen TARGET has both its source schema and its generated output file. */
export function verdictForGeneratedCoverage({ missing }) {
  if (!missing || missing.length === 0) {
    return {
      ruleId: 'contract-projection/coverage-complete',
      status: 'pass',
      reason: 'Every declared wire record has a source schema and a generated TS/Zod file.',
    };
  }
  return {
    ruleId: 'contract-projection/coverage-gap',
    status: 'fail',
    reason:
      'These declared wire records are missing a source schema or a generated file: ' +
      missing.join(', ') +
      '. Generate them (`node scripts/codegen/gen-wire-schema-types.mjs`) or fix the TARGETS path.',
  };
}
