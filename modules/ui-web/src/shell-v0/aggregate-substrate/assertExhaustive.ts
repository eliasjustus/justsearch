// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 / 511-followup — Field-role classification + behavioral
 * Pass-8 mirror helpers.
 *
 * The original tempdoc 511 used `Record<keyof T, true>` for the
 * "consumed declaration." Critical analysis surfaced that this is
 * fake exhaustiveness: it enforces that every key is listed, but
 * NOT that the strategy actually reads each field. A strategy can
 * list every key and consume only `id`; the gate still passes.
 *
 * 511-followup replaces the boolean record with a role record
 * (`FieldRole`) and adds a behavioral assertion helper. The per-
 * strategy test mutates each field in the reference aggregate and
 * asserts:
 *   - Visual / Gate roles → mutation MUST produce a rendered-output
 *     diff (the strategy actually reads the field).
 *   - Routing / Elided roles → mutation MUST NOT produce a diff at
 *     the strategy level (the field is passed downstream by ID, or
 *     intentionally not consumed).
 *
 * This catches the "fake consumption" failure mode at test time:
 * the strategy claims Visual but renders nothing using the field →
 * mutating the field produces no diff → behavioral test fails.
 */

/**
 * Role each wire field plays in a given canonical strategy.
 *
 *   - 'visual':  mutation produces a rendered-output diff
 *                (the strategy reads the field directly).
 *   - 'gate':    field gates the strategy's return — mutation MAY
 *                cause `nothing` to be returned (audience filter).
 *   - 'routing': the strategy passes the aggregate identity
 *                downstream (e.g., to OpButton via operation-id);
 *                the downstream consumer reads the field via
 *                catalog lookup. Mutation MUST NOT change the
 *                strategy-level output.
 *   - 'elided':  intentionally not consumed by this cell. Mutation
 *                MUST NOT change output. Reintroduce when a
 *                consumer claims the field.
 */
export type FieldRole = 'visual' | 'gate' | 'routing' | 'elided';

/**
 * Per-aggregate field-role classification. Every wire field must
 * appear in the record; TypeScript fails at the literal if a key
 * is missing. The role drives the behavioral test below.
 */
export type FieldRoles<T> = Record<keyof T, FieldRole>;

/**
 * Compile-time assertion that `roles` covers every key of `T`.
 */
export function assertFieldRoles<T>(roles: FieldRoles<T>): void {
  void roles;
}

/** List the keys declared in a roles record. */
export function classifiedKeys<T>(
  roles: FieldRoles<T>,
): ReadonlyArray<keyof T> {
  return Object.keys(roles) as Array<keyof T>;
}

/** Subset of keys whose role is one of the given roles. */
export function keysWithRole<T>(
  roles: FieldRoles<T>,
  ...rolesToMatch: FieldRole[]
): ReadonlyArray<keyof T> {
  const set = new Set<FieldRole>(rolesToMatch);
  return classifiedKeys(roles).filter((k) => set.has(roles[k]));
}

// ============================================================
// Legacy aliases — kept for back-compat with Track B/A tests
// that haven't migrated yet. Track C-cleanup-pass deletes these.
// ============================================================

/** @deprecated Use FieldRoles<T>. */
export type Consumed<T> = Record<keyof T, true>;

/** @deprecated Use assertFieldRoles<T>. No-op shim. */
export function assertExhaustive<T>(consumed: Consumed<T>): void {
  void consumed;
}

/** @deprecated Use classifiedKeys<T>. */
export function consumedKeys<T>(consumed: Consumed<T>): ReadonlyArray<keyof T> {
  return Object.keys(consumed) as Array<keyof T>;
}
