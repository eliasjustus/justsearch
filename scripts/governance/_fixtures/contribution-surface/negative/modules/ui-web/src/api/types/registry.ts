// Negative fixture barrel — the §4c regression: a hand-authored second shape authority returns.
// The contribution-surface gate must FAIL.
import type { FooWire } from '../generated/schema-types/foo';
import { Bar } from './hand-types'; // ← laundered import from a non-generated module (import-purity fail)

/** A legit derivation — allowed. */
export type Foo = Omit<FooWire, 'x'> & { y: string; bar: Bar };

/** A hand-authored interface — a second shape authority (purity fail). */
export interface Sneaky {
  id: string;
  label: string;
}

/** A bare object-literal alias — also a second shape authority (purity fail). */
export type AlsoSneaky = {
  a: number;
  b: string;
};
