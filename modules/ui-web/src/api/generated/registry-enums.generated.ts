/* eslint-disable */
// GENERATED — do not edit by hand.
// Single authority: the Java enums in io.justsearch.agent.api.registry (TrustTier / Audience / RiskTier / Altitude / ExecutorTag / SourceTier / GateBehavior).
// Regenerate: ./gradlew.bat :modules:app-agent-api:test -PupdateSchemas --tests '*RegistryEnumsTsGenerationTest'
// Drift-checked by RegistryEnumsTsGenerationTest (tempdoc 560 §4.1/§4.3 anti-drift).

export type TrustTier = 'CORE' | 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN';
export type Audience = 'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER';
export type RiskTier = 'LOW' | 'MEDIUM' | 'HIGH';
export type Altitude = 'PRODUCT' | 'DIAGNOSTIC' | 'TRUST' | 'TOOL';
export type ExecutorTag = 'UI' | 'AGENT' | 'CLI';
export type SourceTier = 'TRUSTED' | 'MEDIUM' | 'UNTRUSTED';
export type GateBehavior = 'AUTO' | 'INLINE_CONFIRM' | 'TYPED_CONFIRM' | 'DENY';
