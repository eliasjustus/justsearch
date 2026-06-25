/**
 * SSOT catalog-sync classification vocabulary.
 *
 * The two catalog copies are required to match. When they legitimately don't
 * (rare), or a mirror is retired, a changeset under
 * `gates/ssot-catalog-sync/.changesets/<id>.md` declares one of these (with a
 * `mirror:` frontmatter field naming the affected mirror id):
 *
 *   intentional-divergence
 *     The root and classpath copies are deliberately different (should be
 *     exceptional — the whole point is that they mirror). Requires
 *     tempdoc/adr justification.
 *
 *   mirror-retirement
 *     A mirror entry is being removed from mirrors.json because the file is no
 *     longer dual-copied. Requires tempdoc/adr justification.
 *
 *   emergency-override
 *     Escape hatch for a gate-blocking diff that must land first. Loud.
 */

export const SSOT_SYNC_CLASSIFICATIONS = new Set([
  'intentional-divergence',
  'mirror-retirement',
  'emergency-override',
]);

export const SSOT_SYNC_REQUIRE_JUSTIFICATION = new Set([
  'intentional-divergence',
  'mirror-retirement',
  'emergency-override',
]);

/**
 * Build a Map<mirrorId, classification> from loaded changeset declarations
 * (each names its target mirror via a `mirror:` frontmatter field).
 *
 * @param {Array<{classification: string, frontmatter: Record<string,string>}>} declarations
 */
export function indexClassificationsByMirror(declarations) {
  const byMirror = new Map();
  for (const d of declarations) {
    const mirror = d.frontmatter?.mirror;
    if (mirror) byMirror.set(mirror, d.classification);
  }
  return byMirror;
}
