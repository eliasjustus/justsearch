/**
 * 569 Fix C — gate coverage projects from the role authority; perf budget measures bodies.
 */
import { describe, it, expect } from 'vitest';
import { CONTRAST_PAIRS, measureUiSchema, PERF_BUDGET } from './conformancePolicy.js';
import { COLOR_ROLES } from './roleForegrounds.js';

describe('conformancePolicy', () => {
  it('contrast pairs project one (on,bg) pair per colour role (new role auto-covered)', () => {
    for (const role of COLOR_ROLES) {
      expect(CONTRAST_PAIRS.some((p) => p.fg === role.on && p.bg === role.bg)).toBe(true);
    }
    // and still includes the fixed text-on-surface readability floor
    expect(CONTRAST_PAIRS.some((p) => p.fg === 'text-primary' && p.bg === 'surface-1')).toBe(true);
  });

  it('measureUiSchema counts nodes + max depth', () => {
    const ui = {
      type: 'VerticalLayout',
      elements: [
        { type: 'Control', scope: '#/a' },
        { type: 'HorizontalLayout', elements: [{ type: 'Control', scope: '#/b' }] },
      ],
    };
    const m = measureUiSchema(ui);
    expect(m.nodes).toBe(4); // root + control + hlayout + control
    expect(m.depth).toBe(2);
  });

  it('a pathological uischema exceeds the budget', () => {
    const huge = {
      type: 'VerticalLayout',
      elements: Array.from({ length: PERF_BUDGET.maxNodes + 5 }, () => ({ type: 'Control', scope: '#/x' })),
    };
    expect(measureUiSchema(huge).nodes).toBeGreaterThan(PERF_BUDGET.maxNodes);
  });
});
