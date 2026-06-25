// SPDX-License-Identifier: Apache-2.0
/**
 * Convert policy paths returned by the backend (often serialized as file URLs) into Windows paths
 * that can be passed to Tauri open/reveal commands.
 */
export function fileUrlToWindowsPath(pathOrUrl: string | undefined | null): string | null {
  if (pathOrUrl == null) return null;
  const raw = String(pathOrUrl).trim();
  if (!raw) return null;

  // Jackson serializes java.nio.file.Path as file:///C:/... in our policy API.
  if (raw.toLowerCase().startsWith('file:///')) {
    const withoutScheme = raw.slice('file:///'.length);
    const decoded = safeDecodeURIComponent(withoutScheme);
    return decoded.replace(/\//g, '\\');
  }

  return raw;
}

export function dirnameWindows(path: string | undefined | null): string | null {
  if (path == null) return null;
  const raw = String(path).trim();
  if (!raw) return null;
  const idx = Math.max(raw.lastIndexOf('\\'), raw.lastIndexOf('/'));
  if (idx <= 0) return null;
  return raw.slice(0, idx);
}

export function buildMachinePolicyTemplate(allowlistedManifestShas: string[]): string {
  const unique = Array.from(
    new Set((allowlistedManifestShas ?? []).map((s) => String(s || '').trim().toLowerCase()).filter(Boolean))
  );

  const doc = {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    downloadsEnabled: true,
    onlineAiEnabled: true,
    gpuAccelerationEnabled: true,
    disallowExternalInferenceServers: false,
    allowlists: {
      packManifestSha256: unique,
      modelSha256: [] as string[],
    },
  };
  return JSON.stringify(doc, null, 2);
}

export function buildUserPolicyTemplate(allowlistedManifestShas: string[]): string {
  const unique = Array.from(
    new Set((allowlistedManifestShas ?? []).map((s) => String(s || '').trim().toLowerCase()).filter(Boolean))
  );

  const doc = {
    schemaVersion: 1,
    updatedAt: new Date().toISOString(),
    downloadsEnabled: true,
    onlineAiEnabled: true,
    gpuAccelerationEnabled: true,
    disallowExternalInferenceServers: false,
    allowlists: {
      packManifestSha256: unique,
      modelSha256: [] as string[],
    },
  };
  return JSON.stringify(doc, null, 2);
}

function safeDecodeURIComponent(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}


