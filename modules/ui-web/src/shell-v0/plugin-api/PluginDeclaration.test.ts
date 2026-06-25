import { describe, expect, it } from 'vitest';

import { pluginDeclaration } from './PluginDeclaration.js';
import type { PluginManifest } from './plugin-types.js';
import type { PluginManifestWithSignature } from './PluginTrust.js';

function manifest(over: Partial<PluginManifest> = {}): PluginManifest {
  return {
    id: 'acme.notes',
    version: '2.1.0',
    displayName: 'Acme Notes',
    capabilities: {},
    register: () => {},
    contractVersion: '1.1',
    tagNamespace: 'acme.notes',
    ...over,
  };
}

describe('pluginDeclaration — §4.1 manifest→declaration projection', () => {
  it('projects the shared declaration axes faithfully from the manifest', () => {
    const decl = pluginDeclaration(manifest());
    expect(decl.id).toBe('acme.notes');
    expect(decl.presentation.label).toBe('Acme Notes');
    expect(decl.provenance.version).toBe('2.1.0');
    // vendor is the namespace head of the id.
    expect(decl.provenance.vendor).toBe('acme');
    // A plugin's surfaces are rendered by the shell into the human UI.
    expect(decl.audience).toBe('USER');
    expect(decl.consumers).toEqual([{ consumerId: 'shell-host', audience: 'USER' }]);
  });

  it('derives UNTRUSTED_PLUGIN from an unsigned manifest (the FE trust signal)', () => {
    expect(pluginDeclaration(manifest()).provenance.trustTier).toBe('UNTRUSTED_PLUGIN');
  });

  it('derives TRUSTED_PLUGIN from a signed manifest', () => {
    const signed = manifest() as PluginManifestWithSignature;
    signed.signature = 'cosign-bundle-opaque';
    expect(pluginDeclaration(signed).provenance.trustTier).toBe('TRUSTED_PLUGIN');
  });

  it('honours an explicit registration tier over the signature heuristic (compiled-in plugins)', () => {
    // A compiled-in core plugin is CORE regardless of signature presence.
    expect(pluginDeclaration(manifest(), 'CORE').provenance.trustTier).toBe('CORE');
    expect(pluginDeclaration(manifest(), 'TRUSTED_PLUGIN').provenance.trustTier).toBe(
      'TRUSTED_PLUGIN',
    );
  });

  it('handles a bare (namespace-less) id by using the whole id as vendor', () => {
    expect(pluginDeclaration(manifest({ id: 'core' })).provenance.vendor).toBe('core');
  });
});
