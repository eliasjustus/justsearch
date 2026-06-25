// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 7 — the LOCAL constrained completion: the concrete {@link ConstrainedCompletion} that
 * backs the generative authoring origin with the on-device model.
 *
 * It routes through the host AI capability's one-shot {@link PluginAI.invokeShape} against the
 * `core.extract` conversation shape — the backend's structured-output shape (JSON-schema
 * constraint + validate-and-retry loop). The presentation schema is passed as the `schema` field,
 * so the model is driven toward schema-valid JSON; {@link certifyPresentation} (downstream) is the
 * final gate for the remaining semantics. "Reason-free-then-emit-constrained" is honoured by the
 * shape's own retry loop — this module only supplies the prompt + schema and returns the text.
 */
import type { ConstrainedCompletion } from './authorPresentation.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

/** The backend structured-output shape id (ExtractShape — schema-constrained + retry). */
export const EXTRACT_SHAPE_ID = 'core.extract';

/** Build a {@link ConstrainedCompletion} that emits via the local model through `host.ai`. */
export function createLocalConstrainedCompletion(host: PluginHostApi): ConstrainedCompletion {
  return async ({ prompt, responseFormat }) => {
    const res = await host.ai.invokeShape(EXTRACT_SHAPE_ID, {
      prompt,
      schema: JSON.stringify(responseFormat),
    });
    return res.text;
  };
}
