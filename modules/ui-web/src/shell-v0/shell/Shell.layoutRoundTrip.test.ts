// @vitest-environment happy-dom

/**
 * Round-trip property test for Shell's SerializedLayout (F14; see
 * docs/tempdocs/547-ui-web-static-analysis-findings.md). This is a sweep
 * target from the persistence round-trip defect class (cf. F9 / F11 in
 * UserStateDocument): serialize -> store -> deserialize, where the
 * deserialize step drops or mis-rebinds a field.
 *
 * F14: `deserializeArea` reset ALL split-area proportions to equal weight
 * whenever any child pane failed to resolve, instead of preserving the
 * surviving panes' saved sizes (the behavior its own comment described as
 * "truncate sizes too"). A layout referencing an unresolved pane (e.g. an
 * uninstalled plugin surface, or a restore that ran before addPane) lost
 * the sizing of EVERY pane in that split, not just the dropped one.
 */

import { describe, it, expect } from 'vitest';
import { deserializeArea, type SerializedAreaConfig } from './Shell.js';
import type { LitWidget } from './LitWidget.js';

// deserializeArea only does `widgetsById.get(id)` + identity checks; it
// never calls a method on the stored value, so a tagged stub suffices.
function widgetMap(...ids: string[]): Map<string, LitWidget> {
  const m = new Map<string, LitWidget>();
  for (const id of ids) m.set(id, { id } as unknown as LitWidget);
  return m;
}

const tab = (id: string): SerializedAreaConfig => ({
  type: 'tab-area',
  widgets: [id],
  currentIndex: 0,
});

describe('Shell layout round-trip — deserializeArea (F14)', () => {
  it('preserves split proportions when all panes resolve (control)', () => {
    const layout: SerializedAreaConfig = {
      type: 'split-area',
      orientation: 'horizontal',
      children: [tab('a'), tab('b'), tab('c')],
      sizes: [0.5, 0.3, 0.2],
    };
    const result = deserializeArea(layout, widgetMap('a', 'b', 'c'));
    expect(result?.type).toBe('split-area');
    expect((result as unknown as { sizes: number[] }).sizes).toEqual([
      0.5, 0.3, 0.2,
    ]);
  });

  it("preserves surviving panes' proportions when one pane is dropped", () => {
    // 'b' is not registered -> it drops from the restored layout. 'a' and
    // 'c' must keep their saved sizes (0.5 and 0.2); only b's 0.3 is
    // removed. Pre-fix this reset every size to equal weight ([1, 1]).
    const layout: SerializedAreaConfig = {
      type: 'split-area',
      orientation: 'horizontal',
      children: [tab('a'), tab('b'), tab('c')],
      sizes: [0.5, 0.3, 0.2],
    };
    const result = deserializeArea(layout, widgetMap('a', 'c'));
    const split = result as unknown as { children: unknown[]; sizes: number[] };
    expect(split.children).toHaveLength(2);
    expect(split.sizes).toEqual([0.5, 0.2]);
  });

  it('drops the whole split when no pane resolves', () => {
    const layout: SerializedAreaConfig = {
      type: 'split-area',
      orientation: 'vertical',
      children: [tab('x'), tab('y')],
      sizes: [0.5, 0.5],
    };
    expect(deserializeArea(layout, widgetMap())).toBeNull();
  });
});
