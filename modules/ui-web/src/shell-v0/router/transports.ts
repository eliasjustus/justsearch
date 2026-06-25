// SPDX-License-Identifier: Apache-2.0
/**
 * TransportTag — TS mirror of `modules/app-agent-api/.../registry/TransportTag.java`.
 *
 * Per slice 489 §17.5: the FE intent layer stamps the transport of origin on
 * every dispatch. Values mirror the Java enum exactly; the backend constructs
 * the trusted {@link InvocationProvenance} from the matching enum value.
 *
 * String-literal-union (rather than a TS enum) is the codebase convention for
 * wire types — `wire-types.ts` baseline uses string unions for proto enums.
 */
export type TransportTag =
  | 'URL_BAR'
  | 'URL_DEEPLINK'
  | 'LLM_EMISSION'
  | 'PALETTE'
  | 'BUTTON'
  | 'RAIL'
  | 'AGENT_LOOP'
  | 'MCP'
  | 'PLUGIN_EMITTED'
  | 'SCHEDULED'
  | 'RULE_ENGINE'
  | 'SYSTEM_INTERNAL';

/** HTTP header name set on /api/operations/{id}/invoke requests. */
export const TRANSPORT_HEADER = 'X-JustSearch-Transport';
