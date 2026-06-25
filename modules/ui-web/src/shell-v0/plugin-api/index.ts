// SPDX-License-Identifier: Apache-2.0
/**
 * Public entry point for the Shell V0 Plugin API. Re-exports the
 * V1.1 contract types + the registry. V1.5+ may add a loader entry
 * point here when lazy ES-module loading lands.
 */

export type {
  PluginCapabilities,
  PluginContribution,
  PluginHostApi,
  PluginManifest,
  PluginRegisterFn,
  PluginSurfaceContribution,
  PluginUnregisterFn,
  SurfacePortContext,
  SurfacePortHandler,
  OperationResult,
  NotificationOptions,
  ConfirmDialogOptions,
  HostFetchInit,
  CatalogEntry,
  InspectorItem,
  Unsubscribe,
} from './plugin-types.js';
export { PLUGIN_CONTRACT_VERSION } from './plugin-types.js';

export {
  PluginRegistry,
  audienceFloorForTier,
  type InstalledPlugin,
  type PluginSurfaceEntry,
  type PluginTrustTier,
} from './PluginRegistry.js';

// Slice 465 (V1.5 alpha) + 477 H2.1 (V1.5.1) — plugin loader.
// Source-text fetch + Compartment.evaluate (separate realm).
// Slice 469 — reloadPlugin composition for hot-reload.
export {
  loadPluginFromUrl,
  reloadPlugin,
  PluginLoadError,
  type SourceFetcher,
} from './PluginLoader.js';

// Slice 466 — SES Compartment factory for plugin isolation
// (V1.5 alpha: Compartment-only; full lockdown() ships V1.5.1).
export {
  createPluginCompartment,
  isSesAvailable,
  type PluginEndowments,
} from './PluginCompartment.js';

// Slice 467 — capability-bundle factory per trust tier (V1.5 alpha:
// uniform across tiers; per-tier attenuation ships V1.5.1).
export { buildCapabilityBundle } from './PluginCapabilityBundle.js';

// Slice 477 H2.1 — buildDefaultEndowments for compartment loader
export { buildDefaultEndowments } from './PluginCompartment.js';

// Slice 468 — plugin trust verification (V1.5 alpha: signature-
// presence stub; full Sigstore chain backend-side V1.5.1).
export {
  verifyPluginTrust,
  trustVerdictToTier,
  type PluginManifestWithSignature,
  type PluginTrustVerdict,
} from './PluginTrust.js';

// Tempdoc 560 §4.2 — the @kernel/* capability boundary with resolver-time module substitution
// (the access path; trust attenuation is which module the resolver returns, not an if() in a body).
export {
  KERNEL_SPECIFIERS,
  resolveKernelModules,
  type KernelSpecifier,
  type KernelModuleDescriptor,
} from './KernelResolver.js';

// Tempdoc 560 §4.4 — the constrained PRESENTATION component vocabulary (an untrusted plugin
// surface may only mount the host jf-* vocabulary, never a second presentation authority).
export {
  HOST_PRESENTATION_PREFIX,
  isConstrainedPresentationTag,
  isPresentationAdmissible,
  assertConstrainedPresentation,
} from './PresentationVocabulary.js';

// Tempdoc 560 §4.1 — the unified declaration model + the manifest→declaration
// projection (the FE side of "PluginManifest projected onto the backend Plugin").
export { pluginDeclaration } from './PluginDeclaration.js';
export type {
  SubstrateDeclaration,
  SubstrateProvenance,
  SubstrateConsumerHook,
  SubstrateTrustTier,
  SubstrateAudience,
  PluginDeclaration,
} from './plugin-types.js';

// Slice 477 H2.3 — TrustChannel as Provenance mint-site (478 §4.D).
export {
  StubTrustChannel,
  RemoteTrustChannel,
  type TrustChannel,
  type TrustVerdict,
  type TrustEvidence,
} from './TrustChannel.js';

// Tempdoc 507 §3.3 — CorePlugin manifest for core surface registration
export { createCorePluginManifest, CORE_PLUGIN_ID } from './CorePlugin.js';

// Tempdoc 507 §3.1 — HostApiImpl factory and deps
export { createHostApi, type HostApiDeps } from './HostApiImpl.js';

// Slice 477 H1 — session-singleton PluginRegistry. Production
// callers (Settings UI, future loaders) use this instead of
// constructing their own instance.
export {
  getSessionPluginRegistry,
  __resetSessionRegistryForTest,
  __seedSessionRegistryForTest,
} from './sessionRegistry.js';
