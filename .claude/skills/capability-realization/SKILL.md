---
description: "Review an implemented tempdoc to determine whether its claimed or concretely implied capabilities are connected, discoverable, and usable through their intended product and development paths."
---

After the current tempdoc's implementation is complete, perform a no-edit capability-realization review of the resulting work.

Begin with a theoretical expectation pass. Identify every capability the tempdoc and implementation claim or concretely imply, grounding implied capabilities in the tempdoc or repository evidence. Determine which product consumer, supported development workflow, and fresh-agent discovery path are intended for each. Do not demand surfaces outside the capability's intended audience. For a fresh-agent path, include finding the capability and any supported activation, setup, or recovery needed to use it.

Then challenge each capability by tracing it from the implementation toward those intended paths. Actively look for missing, stale, bypassed, or unreachable links. Do not treat configuration, registration, compilation, passing tests, or evidence that stops at an intermediate layer as proof. Call a capability realized only when positive evidence reaches its intended consumer or supported workflow and shows that the capability is discoverable and usable there. If proof is incomplete, distinguish confirmed gaps, partial realization, and unverified risks. Do not infer a confirmed gap merely because a path could not be exercised or proved; without evidence of a broken or missing intended link, treat it as unverified.

Treat this as a no-edit, non-destructive review. Do not modify repository content, fix findings, update the tempdoc, or create commits. Use only non-destructive investigation and verification, and stop before altering user data or durable runtime state.

Report to the user in chat first with a concise list ranked by your evaluated importance. For each material item, state the capability or gap, why it matters, and the evidence and confidence. Do not invent findings. If no material gaps exist, say so and briefly state what was checked.
