// SPDX-License-Identifier: Apache-2.0
/**
 * Fetches component-level health diagnostics from /api/health.
 *
 * Used when the initial connection fails to identify which subprocess
 * (Head, Worker, Inference) is down. The /api/health endpoint is served
 * by the Head process and does not require gRPC to the Worker, so it
 * can respond even when the Worker has crashed.
 */

export interface HealthDiagnostic {
  headState: string;
  workerState: string;
  workerReasonCode: string | null;
  inferenceState: string;
  inferenceReasonCode: string | null;
}

/**
 * Attempt a single fetch to /api/health to gather diagnostic info.
 * Returns null if the backend is completely unreachable.
 */
export async function fetchHealthDiagnostic(baseUrl: string): Promise<HealthDiagnostic | null> {
  try {
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), 3000);
    const res = await fetch(`${baseUrl}/api/health`, { signal: controller.signal });
    clearTimeout(timeoutId);

    if (!res.ok) return null;
    const data = await res.json();

    const components = data?.components as Record<string, { state?: string; reason_code?: string }> | undefined;
    return {
      headState: components?.head?.state ?? 'UNKNOWN',
      workerState: components?.worker?.state ?? 'UNKNOWN',
      workerReasonCode: components?.worker?.reason_code ?? null,
      inferenceState: components?.inference?.state ?? 'UNKNOWN',
      inferenceReasonCode: components?.inference?.reason_code ?? null,
    };
  } catch {
    return null;
  }
}
