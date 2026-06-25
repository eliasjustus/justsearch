// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 497 — User-friendly error messages for streaming failures.
 *
 * Maps technical error codes from consumeShapeStream to user-facing messages.
 */

export function friendlyStreamError(err: unknown): string {
  if (err instanceof Error) {
    const code = (err as Error & { code?: string }).code;
    if (code === 'STREAM_INCOMPLETE') {
      return 'Connection lost — the response was interrupted.';
    }
    return err.message;
  }
  return String(err);
}
