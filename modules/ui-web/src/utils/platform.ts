// SPDX-License-Identifier: Apache-2.0
export function isMacOS(): boolean {
  try {
    if (typeof navigator === 'undefined') return false;
    // Prefer UA-CH (Chromium) when available; fall back to navigator.platform.
    const anyNav = navigator as unknown as { userAgentData?: { platform?: string } };
    const platformRaw = anyNav.userAgentData?.platform ?? (navigator as any).platform ?? '';
    const platform = String(platformRaw || '').toLowerCase();
    return platform.includes('mac');
  } catch {
    return false;
  }
}


