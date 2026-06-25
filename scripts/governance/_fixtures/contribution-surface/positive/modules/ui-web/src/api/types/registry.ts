// Positive fixture barrel — every wire-shaped type derives from the generated wire; the only
// hand object is the allowlisted catalog envelope. The contribution-surface gate must PASS.
import type { FooWire } from '../generated/schema-types/foo';
import { z } from 'zod';

/** A derived type — allowed (Omit<Wire> & ergonomic overrides; RHS starts with Omit, not `{`). */
export type Foo = Omit<FooWire, 'x'> & { y: string };

/** A derived nested type — allowed (NonNullable over an indexed access). */
export type FooHistory = NonNullable<FooWire['history']>;

/** A discriminator string-union — allowed. */
export type Category = 'A' | 'B' | 'C';

/** A scalar ref — allowed. */
export type FooRef = string;

/** The catalog envelope — an allowlisted hand object (not a wire entry shape). */
export interface FooCatalog {
  schemaVersion: string;
  entries: Foo[];
}

/** A Zod value const — ignored (a value, not a shape authority). */
export const fooSchema = z.object({ y: z.string() });
