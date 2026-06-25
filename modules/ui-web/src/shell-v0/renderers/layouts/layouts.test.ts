/**
 * Tester + dispatch tests for the slice 3a.0 layout renderers.
 *
 * Covers: vertical / horizontal / group / categorization testers
 * and the layoutDispatch.resolveSchema JSON-pointer resolution.
 *
 * Render tests deferred until happy-dom / jsdom is added.
 */

import { describe, expect, it } from 'vitest';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { RANK_LAYOUT, dispatchRenderer } from '../registry.js';
import { verticalLayoutTester } from './VerticalLayout.js';
import { horizontalLayoutTester } from './HorizontalLayout.js';
import { groupLayoutTester } from './GroupLayout.js';
import { categorizationLayoutTester } from './CategorizationLayout.js';
import { resolveSchema } from './layoutDispatch.js';

const ANY_SCHEMA: JsonSchema = { type: 'object' };

describe('verticalLayoutTester', () => {
  it('matches type:VerticalLayout', () => {
    expect(
      verticalLayoutTester(
        { type: 'VerticalLayout' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe(RANK_LAYOUT);
  });

  it('does NOT match other layout types', () => {
    expect(
      verticalLayoutTester(
        { type: 'HorizontalLayout' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe(-1);
    expect(
      verticalLayoutTester({ type: 'Group' } as UISchemaElement, ANY_SCHEMA),
    ).toBe(-1);
    expect(
      verticalLayoutTester({ type: 'Control' } as UISchemaElement, ANY_SCHEMA),
    ).toBe(-1);
  });
});

describe('horizontalLayoutTester', () => {
  it('matches type:HorizontalLayout', () => {
    expect(
      horizontalLayoutTester(
        { type: 'HorizontalLayout' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe(RANK_LAYOUT);
  });

  it('does NOT match other types', () => {
    expect(
      horizontalLayoutTester(
        { type: 'VerticalLayout' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe(-1);
  });
});

describe('groupLayoutTester', () => {
  it('matches type:Group', () => {
    expect(
      groupLayoutTester({ type: 'Group' } as UISchemaElement, ANY_SCHEMA),
    ).toBe(RANK_LAYOUT);
  });

  it('does NOT match other types', () => {
    expect(
      groupLayoutTester(
        { type: 'VerticalLayout' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe(-1);
  });
});

describe('categorizationLayoutTester', () => {
  it('matches type:Categorization', () => {
    expect(
      categorizationLayoutTester(
        { type: 'Categorization' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe(RANK_LAYOUT);
  });

  it('does NOT match Category (sub-element of Categorization)', () => {
    expect(
      categorizationLayoutTester(
        { type: 'Category' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe(-1);
  });
});

describe('resolveSchema', () => {
  // Use loose typing here — JSON Forms' JsonSchema is a union of v4 and v7,
  // and TypeScript's structural inference picks the wrong arm for nested
  // schemas with optional fields like `format`.
  const root = {
    type: 'object',
    properties: {
      email: { type: 'string', format: 'email' },
      profile: {
        type: 'object',
        properties: {
          age: { type: 'integer' },
        },
      },
    },
  } as JsonSchema;

  it('returns root for # / empty pointer', () => {
    expect(resolveSchema(root, '#')).toBe(root);
    expect(resolveSchema(root, '')).toBe(root);
    expect(resolveSchema(root, undefined)).toBe(root);
  });

  it('resolves single-property pointer', () => {
    const resolved = resolveSchema(root, '#/properties/email');
    expect(resolved).toBeDefined();
    expect((resolved as { type?: string }).type).toBe('string');
    expect((resolved as { format?: string }).format).toBe('email');
  });

  it('resolves nested-property pointer', () => {
    const resolved = resolveSchema(root, '#/properties/profile/properties/age');
    expect(resolved).toBeDefined();
    expect((resolved as { type?: string }).type).toBe('integer');
  });

  it('returns null for unresolvable pointer', () => {
    expect(resolveSchema(root, '#/properties/missing')).toBeNull();
  });
});

describe('dispatchRenderer (layouts)', () => {
  it('selects vertical-layout for VerticalLayout', () => {
    expect(
      dispatchRenderer(
        { type: 'VerticalLayout' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe('jf-vertical-layout');
  });

  it('selects horizontal-layout for HorizontalLayout', () => {
    expect(
      dispatchRenderer(
        { type: 'HorizontalLayout' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe('jf-horizontal-layout');
  });

  it('selects group-layout for Group', () => {
    expect(
      dispatchRenderer({ type: 'Group' } as UISchemaElement, ANY_SCHEMA),
    ).toBe('jf-group-layout');
  });

  it('selects categorization-layout for Categorization', () => {
    expect(
      dispatchRenderer(
        { type: 'Categorization' } as UISchemaElement,
        ANY_SCHEMA,
      ),
    ).toBe('jf-categorization-layout');
  });
});

describe('rendererRegistry exports (Phase 4 update)', () => {
  it('contains all 13 renderers (1 x-ui-renderer + 8 controls + 4 layouts)', async () => {
    // Tempdoc 543 §13.3.1 (Slice 5) added the x-ui-renderer
    // dispatcher at the top of the registry.
    const { rendererRegistry } = await import('../registry.js');
    expect(rendererRegistry).toHaveLength(13);
    const tags = rendererRegistry.map((e) => e.tag);
    // x-ui-renderer dispatcher (Slice 5)
    expect(tags).toContain('jf-x-ui-renderer-control');
    // Layouts
    expect(tags).toContain('jf-vertical-layout');
    expect(tags).toContain('jf-horizontal-layout');
    expect(tags).toContain('jf-group-layout');
    expect(tags).toContain('jf-categorization-layout');
  });
});
