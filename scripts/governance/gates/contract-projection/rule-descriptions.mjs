/**
 * Rule descriptions for the contract-projection gate (tempdoc 564 facet 4e).
 * Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const CONTRACT_PROJECTION_RULE_DESCRIPTIONS = {
  'contract-projection/schema-types-drift':
    'The generated FE wire types + Zod schemas (api/generated/schema-types/) drifted from the ' +
    'codegen output — either a hand-edit of a generated file, or an SSOT/schemas change that was ' +
    'not regenerated. Run `node scripts/codegen/gen-wire-schema-types.mjs` and commit the result.',
  'contract-projection/schema-types-fresh':
    'The generated wire types + Zod schemas match the codegen output (healthy).',
  'contract-projection/duplicate-wire-type':
    'A hand-authored copy of a migrated wire type exists outside api/generated/**. A migrated wire ' +
    'record is the single generated projection; the second copy is unrepresentable by mandate ' +
    '(564 facet 4d). Delete it and import the generated type (re-export if a stable path is needed).',
  'contract-projection/single-authority':
    'Every migrated wire type has exactly one generated authority — no hand copy (healthy).',
  'contract-projection/coverage-gap':
    'A declared wire record (codegen TARGETS) is missing its source JSON Schema or its generated ' +
    'TS/Zod file. Generate it or fix the TARGETS path.',
  'contract-projection/coverage-complete':
    'Every declared wire record has a source schema and a generated TS/Zod file (healthy).',
  'contract-projection/register-drift':
    'governance/contract-surfaces.v1.json (the FE-contract register) and the codegen TARGETS declare ' +
    'different wire-record sets. Keep them in lockstep — the register is the human-facing declaration ' +
    'of the 530 contract surface; TARGETS is the generator input.',
  'contract-projection/register-coherent':
    'The contract-surfaces register and the codegen TARGETS declare the same wire records (healthy).',
  'contract-projection/consumer-broken':
    'A declared consumer in contract-surfaces.v1.json does not exist or no longer imports the record’s ' +
    'generated module. Fix the path or the import.',
  'contract-projection/consumers-resolve':
    'Every declared contract consumer exists and imports its generated wire module (healthy).',
  'contract-projection/undeclared-consumer':
    'An FE file imports a generated wire module but is not a declared consumer in ' +
    'contract-surfaces.v1.json. The FE-contract is a declared 530 surface — register every ' +
    'parse-boundary reader so coverage stays honest (cf. execution-surface/undeclared-surface).',
  'contract-projection/consumers-declared':
    'Every FE consumer of a generated wire module is declared in the register (healthy).',
};
