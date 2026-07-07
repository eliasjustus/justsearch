// SPDX-License-Identifier: Apache-2.0
/**
 * Dev-mode detection (Vite sets `import.meta.env.DEV`). A separate module so the
 * wire-contract posture split (tempdoc 683: dev throws, prod degrades) has a
 * mockable seam — tests pin either posture via `vi.mock('./devMode', { spy: true })`.
 */
export function isDevMode(): boolean {
  try {
    return (import.meta as any).env?.DEV === true;
  } catch {
    return false;
  }
}
