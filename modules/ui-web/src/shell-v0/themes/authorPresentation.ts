// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 7 — the generative (LLM) authoring origin.
 *
 * A user describes a vibe; a CONSTRAINED model completion emits a Presentation Declaration
 * whose response_format IS {@link presentationDeclarationJsonSchema} — so the output is
 * schema-valid by construction (the model literally cannot emit an unknown token or a
 * reserved component). The result is then certified ({@link certifyPresentation}) for the
 * remaining semantics (contrast floor). This is the same artifact as a hand-authored or
 * built-in declaration with a different ORIGIN (Move 1) — it flows through the same gate.
 *
 * Reason-free-then-emit-constrained (CRANE): the injected `complete` may run unconstrained
 * reasoning and then constrain only the final JSON emission; this module is agnostic to how
 * `complete` is implemented (the production impl posts to the local inference path with
 * `SamplingParams.responseFormat` set to the schema).
 */
import {
  certifyPresentation,
  describeConformanceError,
  type CertifyResult,
} from './conformanceGate.js';
import { presentationDeclarationJsonSchema } from './presentationSchema.js';

/** A constrained chat completion: given a prompt + a response-format schema, return the raw JSON text. */
export type ConstrainedCompletion = (request: {
  readonly prompt: string;
  readonly responseFormat: object;
}) => Promise<string>;

const SYSTEM_PREAMBLE =
  'You author a JustSearch Presentation Declaration (theme + layout) from the user\'s ' +
  'description. Output ONLY JSON matching the provided schema — no prose. Choose tokens ' +
  'and components only from the schema\'s allowed values.';

/** Build the authoring prompt for the model. */
export function buildAuthoringPrompt(userPrompt: string): string {
  return `${SYSTEM_PREAMBLE}\n\nUser request: ${userPrompt}`;
}

/**
 * 569 §19 Seam 2 + §18.E #1 — build the SELF-REPAIR prompt: the original request, the model's prior
 * (rejected) output, and the STRUCTURED gate verdict rendered to prose, asking for a corrected JSON.
 * The structured verdict (Phase 1) is what makes this loop possible — the model gets exact reasons.
 */
function buildRepairPrompt(userPrompt: string, priorOutput: string, errors: readonly string[]): string {
  return (
    `${buildAuthoringPrompt(userPrompt)}\n\n` +
    `Your previous attempt was REJECTED by the conformance gate:\n${priorOutput}\n\n` +
    `Fix these specific problems and return ONLY the corrected JSON:\n` +
    errors.map((e) => `- ${e}`).join('\n')
  );
}

const INVALID_JSON: CertifyResult = {
  verdict: {
    ok: false,
    errors: [{ kind: 'invalid-json', message: 'model output was not valid JSON' }],
    quarantinedSurfaces: [],
    quarantinedLayout: false,
  },
  declaration: null,
};

/**
 * Author a Presentation Declaration from a natural-language prompt via a constrained completion, then
 * certify it — with a BOUNDED SELF-REPAIR loop (569 §19 / §18.E #1): on a certify (or parse) failure
 * the structured verdict is fed back to the model for up to `maxRepairs` corrected attempts. Worst case
 * returns the last failing `CertifyResult` (the caller degrades to the default). Provenance is
 * HOST-asserted (`origin: llm`, overriding anything the model emits) before each certify.
 */
export async function authorPresentationFromPrompt(
  userPrompt: string,
  complete: ConstrainedCompletion,
  opts?: { readonly maxRepairs?: number },
): Promise<CertifyResult> {
  const maxRepairs = opts?.maxRepairs ?? 1;
  let prompt = buildAuthoringPrompt(userPrompt);
  let last: CertifyResult = INVALID_JSON;

  for (let attempt = 0; attempt <= maxRepairs; attempt++) {
    const raw = await complete({ prompt, responseFormat: presentationDeclarationJsonSchema });
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      last = INVALID_JSON;
      prompt = buildRepairPrompt(userPrompt, raw, ['the output was not valid JSON — emit only JSON']);
      continue;
    }
    if (parsed !== null && typeof parsed === 'object') {
      parsed = { ...(parsed as Record<string, unknown>), origin: { kind: 'llm' } };
    }
    last = certifyPresentation(parsed);
    if (last.verdict.ok) return last;
    prompt = buildRepairPrompt(userPrompt, raw, last.verdict.errors.map(describeConformanceError));
  }
  return last;
}
