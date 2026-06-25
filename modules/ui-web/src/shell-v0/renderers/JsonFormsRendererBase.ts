// SPDX-License-Identifier: Apache-2.0
/**
 * Base class for Lit-based JSON Forms renderers.
 *
 * Per slice 3a.0 §B.A.2: JSON Forms has no first-party Lit binding.
 * The hand-built path is a small Lit base class that exposes
 * `RendererProps` as reactive properties; per-control + per-layout
 * subclasses extend it.
 *
 * Renderers receive the standard JSON Forms props on each render:
 * - `schema` — the JSON Schema fragment for this control / layout
 * - `uischema` — the UI Schema fragment (Control, Layout, etc.)
 * - `path` — JSON-pointer-like data path (e.g., `/user/email`)
 * - `data` — the current data value at `path` (for controls)
 * - `errors` — validation errors at `path` (string, may be empty)
 * - `enabled` — whether the control accepts input
 * - `visible` — whether the control should render at all
 * - `onChange` — callback to update data: `(value, path) => void`
 *
 * Subclasses implement `render()` (Lit's render hook) using these
 * properties. They typically dispatch `onChange` from input event
 * handlers; the host (the `JsonForms` adapter) updates the central
 * data store and re-renders with new props.
 *
 * The reactive properties are declared via the static `properties`
 * accessor (per Lit's non-decorator API) to avoid the
 * `experimentalDecorators` tsconfig dependency that the React side
 * of `modules/ui-web` doesn't enable.
 */

import { JfElement } from '../primitives/JfElement.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import type { RendererUserConfig } from './userConfig.js';

/** Callback invoked by a control when its data changes. */
export type RendererOnChange = (value: unknown, path: string) => void;

/**
 * Normative renderer property contract. Every conforming renderer
 * (substrate or plugin) declares these reactive properties as a Lit
 * `static properties` accessor + `declare` typed fields + constructor
 * defaults. Slice 3a.1.5 promotes this from the previous source-comment
 * convention to a packet-level normative spec; see
 * `the renderer contract (historical design: retired 421 draft, in git)`
 * for the full contract (tag namespacing, tester contract, dispatch
 * invocation, lifecycle expectations).
 *
 * Plugin authors who implement the contract directly (not via
 * `extends JsonFormsRendererBase`) `implements` this interface to get
 * type-driven conformance checking.
 *
 * The optional `userConfig` field is added by slice 3a.1.7 and is
 * declared optional here so V0 renderers (which don't yet receive it)
 * still satisfy the interface.
 */
export interface RendererProps {
  /** JSON Schema fragment for this renderer. */
  schema: JsonSchema;
  /** UI Schema fragment (Control / Layout / Group / etc.). */
  uischema: UISchemaElement;
  /** JSON-pointer-like data path (e.g., `/user/email`). Empty = root. */
  path: string;
  /** Current data value at `path`. Controls only. */
  data: unknown;
  /** Validation error message at `path`. Empty string = no error. */
  errors: string;
  /** Whether the control accepts input. */
  enabled: boolean;
  /** Whether the control should render. */
  visible: boolean;
  /** Callback for data updates. Subclass invokes from event handlers. */
  onChange: RendererOnChange;
  /**
   * Optional user-supplied dispatch + rendering overrides (slice 3a.1.7).
   * Renderers that participate in user customization read this; renderers
   * that ignore it work unchanged.
   */
  userConfig?: RendererUserConfig;
}

const NOOP_ON_CHANGE: RendererOnChange = () => {
  /* default no-op; the JsonForms adapter wires this on mount */
};

/**
 * Reactive property bag exposed by every renderer. Mirrors JSON Forms'
 * `RendererProps` shape; Lit's reactive-property system makes the
 * fields trigger re-render on change.
 */
export class JsonFormsRendererBase
  extends JfElement
  implements RendererProps
{
  static override properties = {
    schema: { attribute: false },
    uischema: { attribute: false },
    path: { type: String },
    data: { attribute: false },
    errors: { type: String },
    enabled: { type: Boolean },
    visible: { type: Boolean },
    onChange: { attribute: false },
    userConfig: { attribute: false },
  } as const;

  // Property declarations use `declare` (type-only, no initializers) per
  // Lit's class-field-shadowing guidance: class-field initializers with
  // ES2022 + useDefineForClassFields:true overwrite Lit's reactive-property
  // accessors at construction time. Defaults are assigned in the
  // constructor so the accessor stays intact.
  // https://lit.dev/msg/class-field-shadowing

  /** JSON Schema fragment for this renderer. */
  declare schema: JsonSchema;

  /** UI Schema fragment (Control / Layout / Group / etc.). */
  declare uischema: UISchemaElement;

  /** JSON-pointer-like data path (e.g., `/user/email`). Empty = root. */
  declare path: string;

  /** Current data value at `path`. Controls only. */
  declare data: unknown;

  /** Validation error message at `path`. Empty string = no error. */
  declare errors: string;

  /** Whether the control accepts input. */
  declare enabled: boolean;

  /** Whether the control should render. */
  declare visible: boolean;

  /** Callback for data updates. Subclass invokes from event handlers. */
  declare onChange: RendererOnChange;

  /**
   * Optional user-supplied dispatch + rendering overrides (slice 3a.1.7).
   * Threaded through dispatch + propagated to child renderers by layout
   * dispatch. Renderers that participate in user customization read it;
   * renderers that ignore it work unchanged.
   */
  declare userConfig: RendererUserConfig | undefined;

  constructor() {
    super();
    this.schema = {};
    this.uischema = { type: 'Control' } as UISchemaElement;
    this.path = '';
    this.data = undefined;
    this.errors = '';
    this.enabled = true;
    this.visible = true;
    this.onChange = NOOP_ON_CHANGE;
    this.userConfig = undefined;
  }

  /**
   * Helper for subclasses: update `data` and propagate via `onChange`.
   * Called from input event handlers.
   */
  protected updateData(value: unknown): void {
    if (!this.enabled) {
      return;
    }
    this.onChange(value, this.path);
  }
}
