// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest';
import {
  NAVIGATE_TO_SURFACE_EVENT,
  requestSurfaceNavigation,
  type NavigateToSurfaceDetail,
} from './navigateRequest.js';
import { startTask, getTask, upsertMirroredTask, __resetTasksForTest } from '../substrates/tasks/index.js';

describe('navigateRequest seam (tempdoc 609 §R T1.3)', () => {
  it('dispatches a document-level navigate event carrying the surface id', () => {
    const handler = vi.fn();
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, handler);
    requestSurfaceNavigation('core.library-surface');
    document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, handler);

    expect(handler).toHaveBeenCalledTimes(1);
    const detail = (handler.mock.calls[0]![0] as CustomEvent<NavigateToSurfaceDetail>).detail;
    expect(detail.surfaceId).toBe('core.library-surface');
  });
});

describe('Task.originSurfaceId (tempdoc 609 §R T1.3)', () => {
  it('carries originSurfaceId through startTask and upsertMirroredTask', () => {
    __resetTasksForTest();
    const id = startTask({ label: 'Search', originSurfaceId: 'core.search-surface' });
    expect(getTask(id)?.originSurfaceId).toBe('core.search-surface');

    upsertMirroredTask({ id: 'job-1', label: 'Indexing', status: 'running', originSurfaceId: 'core.library-surface' });
    expect(getTask('job-1')?.originSurfaceId).toBe('core.library-surface');
    __resetTasksForTest();
  });
});
