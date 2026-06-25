// Fixture: an UNDECLARED surface — references IndexingJobView but is absent from the register.
// The gate must fail with operation-surface/undeclared-surface.
import type { IndexingJobView } from '../api/indexing.js';
export const sneaky: IndexingJobView | null = null;
