/**
 * Tempdoc 511-followup-D-patches — registry slot-dedup tests.
 *
 * Verifies that `registerAggregateStrategy` replaces (not appends)
 * when called twice with the same `(aggregate, context, rank,
 * source)` slot — the canonical idempotency property the substrate
 * relies on across Vite HMR reloads of bootstrap.ts.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { html } from 'lit';
import {
  registerAggregateStrategy,
  getRegisteredCells,
  __clearAggregateRegistry,
} from './aggregateRegistry.js';

describe('aggregateRegistry — slot dedup (511-followup-D-patches)', () => {
  beforeEach(() => {
    __clearAggregateRegistry();
  });

  it('re-registering the same (aggregate, context, rank, source) slot replaces, not appends', () => {
    const s1 = () => html`<span data-version="1"></span>`;
    const s2 = () => html`<span data-version="2"></span>`;

    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 0,
      strategy: s1 as never,
      source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 0,
      strategy: s2 as never,
      source: 'core',
    });

    const cells = getRegisteredCells();
    expect(cells.length).toBe(1);
    expect(cells[0]).toMatchObject({
      aggregate: 'Operation',
      context: 'button',
      rank: 0,
      source: 'core',
    });
  });

  it('entries at different ranks coexist (plugin override pattern)', () => {
    const core = () => html`<span data-version="core"></span>`;
    const plugin = () => html`<span data-version="plugin"></span>`;

    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 0,
      strategy: core as never,
      source: 'core',
    });
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 1,
      strategy: plugin as never,
      source: { plugin: 'my-plugin' },
    });

    const cells = getRegisteredCells();
    expect(cells.length).toBe(2);
  });

  it('entries from different plugin sources at same rank coexist', () => {
    const p1 = () => html`<span></span>`;
    const p2 = () => html`<span></span>`;

    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 1,
      strategy: p1 as never,
      source: { plugin: 'plugin-a' },
    });
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 1,
      strategy: p2 as never,
      source: { plugin: 'plugin-b' },
    });

    const cells = getRegisteredCells();
    expect(cells.length).toBe(2);
  });

  it('re-registering the same plugin source at same rank replaces', () => {
    const p1 = () => html`<span></span>`;
    const p2 = () => html`<span></span>`;

    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 1,
      strategy: p1 as never,
      source: { plugin: 'plugin-a' },
    });
    registerAggregateStrategy({
      aggregate: 'Operation',
      context: 'button',
      rank: 1,
      strategy: p2 as never,
      source: { plugin: 'plugin-a' },
    });

    const cells = getRegisteredCells();
    expect(cells.length).toBe(1);
  });
});
