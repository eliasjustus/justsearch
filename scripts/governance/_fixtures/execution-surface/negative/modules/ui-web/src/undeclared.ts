// Fixture: an UNDECLARED surface — references SearchTrace but is absent from the register.
// The gate must fail with execution-surface/undeclared-surface.
import type { SearchTrace } from '../api/generated/index.js';
export const sneaky: SearchTrace | null = null;
