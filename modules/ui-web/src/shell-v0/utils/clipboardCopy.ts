// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 486 G35 — clipboardCopy utility.
 *
 * Wraps `navigator.clipboard.writeText` with a uniform success/
 * failure return so call-sites can render feedback (e.g., "Copied!"
 * vs. "Copy failed"). Mirrors the bare clipboard usage already in
 * BrowseSurface (slice 486 G35 audit) but standardizes:
 *
 *   - permission-denied / API-unavailable handling (returns false,
 *     does not throw)
 *   - dev-time `console.warn` on failure for debugging
 *   - happy-dom test-environment compatibility (works under
 *     vitest's `@vitest-environment happy-dom` setup with a
 *     navigator.clipboard mock)
 *
 * Browsers require a user gesture for clipboard writes; calling
 * this from a click handler is the standard pattern. From a
 * non-gesture context (e.g., a setTimeout chain) the call returns
 * false rather than rejecting.
 */

/**
 * Copy `text` to the system clipboard. Returns `true` on success,
 * `false` on any failure (permission denied, API unavailable, etc).
 * Never throws.
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (
    typeof navigator === 'undefined' ||
    !navigator.clipboard ||
    typeof navigator.clipboard.writeText !== 'function'
  ) {
    return false;
  }
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (e) {
    if (typeof console !== 'undefined' && console.warn) {
      console.warn('[clipboardCopy] write failed', e);
    }
    return false;
  }
}
