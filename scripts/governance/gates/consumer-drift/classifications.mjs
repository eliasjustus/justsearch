/**
 * Consumer-drift classification vocabulary — tempdoc 531.
 *
 * When a substrate slot drifts below its `expectedMin` consumer floor and its
 * grace window has expired, the gate fails unless a changeset under
 * `gates/consumer-drift/.changesets/<id>.md` declares one of these
 * classifications (with a `slot:` frontmatter field naming the affected slot):
 *
 *   slot-retraction
 *     The substrate is being deleted because it never earned a consumer
 *     (ADR-0014 / C-018 retraction). The changeset's commit must remove the
 *     slot's `declaredIn` source; the gate verifies the file is gone.
 *
 *   grace-extension
 *     The consumer is genuinely in-flight in a named follow-up. Buys more
 *     time; requires a tempdoc/ADR reference justifying the extension.
 *
 *   emergency-override
 *     Escape hatch for a gate-blocking diff that must land before the
 *     consumer can. Requires explicit justification; appears loudly in SARIF.
 */

export const CONSUMER_DRIFT_CLASSIFICATIONS = new Set([
  'slot-retraction',
  'grace-extension',
  'emergency-override',
]);

/** Classifications that require a `tempdoc:`/`adr:` justification field. */
export const CONSUMER_DRIFT_REQUIRE_JUSTIFICATION = new Set([
  'grace-extension',
  'emergency-override',
]);

/**
 * Build a Map<slotId, classification> from loaded changeset declarations.
 * Each changeset names its target slot via a `slot:` frontmatter field; a
 * changeset without a `slot:` is ignored for per-slot matching (the kernel
 * already validated its `classification` is in the allowed set).
 *
 * @param {Array<{classification: string, frontmatter: Record<string,string>}>} declarations
 * @returns {Map<string, string>}
 */
export function indexClassificationsBySlot(declarations) {
  const bySlot = new Map();
  for (const d of declarations) {
    const slot = d.frontmatter?.slot;
    if (slot) bySlot.set(slot, d.classification);
  }
  return bySlot;
}
