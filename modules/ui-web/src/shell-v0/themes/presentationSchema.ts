// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 7 — the JSON Schema for a Presentation Declaration: the constrained-decoding
 * "grammar" (response_format) a local model emits against. It EMBEDS the closed vocabularies
 * — authorable token names (propertyNames enum) and authorable components (enum) — so the
 * constrained decode itself cannot emit an unknown token or a reserved/chrome component.
 * "The gate is the grammar": the shape is enforced by the schema at sample time, the
 * remaining semantics (contrast floor, etc.) by certifyPresentation. Built from the same
 * vocabularies the rest of the runtime uses, so it can never drift from them.
 */
import { KNOWN_TOKEN_NAMES } from './designTokenTree.js';
import { COMPONENT_TAGS } from '../renderers/component-vocabulary.generated.js';
import { RESERVED_COMPONENTS } from './authorableComponents.js';

const TOKEN_NAMES: readonly string[] = [...KNOWN_TOKEN_NAMES].sort();
const AUTHORABLE_COMPONENTS: readonly string[] = COMPONENT_TAGS.filter(
  (t) => !RESERVED_COMPONENTS.has(t),
);

export const presentationDeclarationJsonSchema = {
  $schema: 'http://json-schema.org/draft-07/schema#',
  title: 'PresentationDeclaration',
  type: 'object',
  additionalProperties: false,
  required: ['schemaVersion', 'id', 'displayName'],
  properties: {
    schemaVersion: { const: 1 },
    id: { type: 'string', pattern: '^[a-z][a-z0-9.-]+$' },
    displayName: { type: 'string', minLength: 1 },
    description: { type: 'string' },
    version: { type: 'string' },
    author: { type: 'string' },
    theme: {
      type: 'object',
      additionalProperties: false,
      required: ['tokens'],
      properties: {
        tokens: {
          type: 'object',
          // Keys are constrained to the closed token vocabulary — the model cannot emit
          // an unknown (or derived-internal) token name.
          propertyNames: { enum: TOKEN_NAMES },
          additionalProperties: { type: 'string' },
        },
      },
    },
    layout: {
      type: 'object',
      additionalProperties: false,
      required: ['regions'],
      properties: {
        regions: {
          type: 'array',
          items: {
            type: 'object',
            additionalProperties: false,
            required: ['id'],
            properties: {
              id: { type: 'string', minLength: 1 },
              // Constrained to the authorable component vocabulary (reserved/chrome excluded).
              component: { enum: AUTHORABLE_COMPONENTS },
              order: { type: 'number' },
              visibleWhen: { type: 'string' },
            },
          },
        },
      },
    },
    body: {
      type: 'object',
      additionalProperties: {
        type: 'object',
        required: ['schema', 'uischema'],
        properties: {
          schema: { type: 'object' },
          uischema: { type: 'object' },
        },
      },
    },
  },
} as const;
