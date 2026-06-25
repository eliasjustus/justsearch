/**
 * Interaction-surface truth table (tempdoc 561 surface tier — the one-interaction-window gate).
 * Sibling of the execution-surface (553) and operation-surface (550) anti-fragmentation gates,
 * but keyed on PLACEMENT cardinality rather than projection lineage.
 *
 * Mechanizes the invariant: "there is exactly ONE visible (USER-audience, RAIL/STAGE) interaction
 * surface — the one window — and every direct-LLM interaction shape (the core interaction-shape set,
 * declared once in CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES) routes into it; a
 * DEEPLINK surface may host a shape but is exempt from the bound because it routes in via the shared
 * view." This is the recurrence guard for the 561 surface-tier defect class: a second visible
 * interaction surface (the standalone core.agent-surface alongside core.unified-chat-surface) — two
 * authorities for one concept, neither subordinate (548's class, applied at the surface tier).
 *
 * Conforms to the kernel truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict when the register file itself is absent. */
export function verdictForMissingRegister({ path }) {
  return {
    ruleId: 'interaction-surface/register-missing',
    status: 'fail',
    reason: `Cannot run the interaction-surface gate: the register was not found at ${path}.`,
  };
}

/** Verdict when a scanned authority source (the surface catalog or the shape catalog) is absent. */
export function verdictForMissingSource({ kind, path }) {
  return {
    ruleId: 'interaction-surface/source-missing',
    status: 'fail',
    reason:
      `Cannot run the interaction-surface gate: the ${kind} was not found at ${path}. The gate ` +
      `parses it as the authority for ${kind === 'shape catalog' ? 'the core interaction-shape set' : 'the surface declarations'}.`,
  };
}

/** Verdict: the core interaction-shape set could not be parsed (empty) — the authority is unreadable. */
export function verdictForEmptyCoreSet({ shapeCatalogPath }) {
  return {
    ruleId: 'interaction-surface/core-set-unreadable',
    status: 'fail',
    reason:
      `The core interaction-shape set (CORE_USER_INTERACTION_SHAPES) parsed empty from ` +
      `${shapeCatalogPath}. This authority drives the whole gate; verify the constant exists as ` +
      `\`Set.of("core.x", ...)\`.`,
  };
}

/**
 * Verdict: the FE mirror (coreInteractionShapes.ts CORE_INTERACTION_SHAPES) has drifted from the
 * Java authority (CORE_USER_INTERACTION_SHAPES). The two must name the same shape set — otherwise
 * the FE could host an interaction mode the gate doesn't bound, or omit one it does (a fork).
 */
export function verdictForMirrorDrift({ onlyInJava, onlyInFe }) {
  if (onlyInJava.length > 0 || onlyInFe.length > 0) {
    const parts = [];
    if (onlyInJava.length > 0) parts.push(`only in the Java authority: ${onlyInJava.join(', ')}`);
    if (onlyInFe.length > 0) parts.push(`only in the FE mirror: ${onlyInFe.join(', ')}`);
    return {
      ruleId: 'interaction-surface/fe-mirror-drift',
      status: 'fail',
      reason:
        `The FE mirror (coreInteractionShapes.ts) and the Java authority ` +
        `(CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES) disagree on the core ` +
        `interaction-shape set — ${parts.join('; ')}. Bring the FE mirror back in sync with the ` +
        `Java authority so the one window's modes are declared once, not forked.`,
    };
  }
  return {
    ruleId: 'interaction-surface/fe-mirror-synced',
    status: 'pass',
    reason: 'The FE mirror equals the Java core interaction-shape authority.',
  };
}

/**
 * Verdict (THE core invariant): more than one VISIBLE (USER-audience, RAIL/STAGE) surface consumes a
 * core interaction shape. The one window must be the sole visible interaction surface; a second one
 * is the 561 surface-tier fork class (two interaction authorities, neither subordinate). DEEPLINK
 * surfaces are exempt — they route into the one window via the shared view.
 */
export function verdictForMultipleInteractionSurfaces({ surfaces, canonical }) {
  if (surfaces.length > 1) {
    const extras = surfaces.filter((s) => s !== canonical);
    return {
      ruleId: 'interaction-surface/multiple-visible-interaction-surfaces',
      status: 'fail',
      reason:
        `${surfaces.length} visible (USER-audience, RAIL/STAGE) surfaces consume a core interaction ` +
        `shape: ${surfaces.join(', ')}. There must be exactly ONE — the one window (${canonical}). ` +
        `Retire or DEEPLINK-demote the extra(s) [${extras.join(', ')}] so every interaction shape ` +
        `routes into the single window (tempdoc 561 surface tier; the 548 two-authorities class).`,
    };
  }
  return {
    ruleId: 'interaction-surface/single-visible-interaction-surface',
    status: 'pass',
    reason: 'At most one visible interaction surface consumes the core interaction shapes (healthy).',
  };
}

/**
 * Verdict: the single visible interaction surface is NOT the declared canonical one window — either
 * it is missing entirely (no surface hosts the interaction shapes visibly) or a different surface
 * took its place. The one window must exist and be the canonical surface.
 */
export function verdictForCanonicalWindow({ canonical, visibleSurfaces }) {
  if (!visibleSurfaces.includes(canonical)) {
    return {
      ruleId: 'interaction-surface/canonical-window-missing',
      status: 'fail',
      reason:
        `The declared canonical one window (${canonical}) is not a visible (USER-audience, ` +
        `RAIL/STAGE) interaction surface. Visible interaction surfaces: ` +
        `[${visibleSurfaces.join(', ') || 'none'}]. The one window must exist and consume the core ` +
        `interaction shapes — restore it, or update canonicalSurface in the register if it was renamed.`,
    };
  }
  return {
    ruleId: 'interaction-surface/canonical-window-present',
    status: 'pass',
    reason: 'The declared canonical one window is the visible interaction surface (healthy).',
  };
}

/**
 * Verdict: a core interaction shape is consumed by NO surface routing into the one window — the
 * canonical window does not declare it among its conversationShapes. A mode that the one window
 * cannot host is an orphaned interaction shape.
 */
export function verdictForUncoveredShapes({ uncovered, canonical }) {
  if (uncovered.length > 0) {
    return {
      ruleId: 'interaction-surface/uncovered-core-shape',
      status: 'fail',
      reason:
        `These core interaction shapes are not consumed by the one window (${canonical}): ` +
        `${uncovered.join(', ')}. Every interaction mode must be hostable by the single window — ` +
        `add them to its conversationShapes, or remove them from CORE_USER_INTERACTION_SHAPES if ` +
        `they are no longer user-facing interaction modes.`,
    };
  }
  return {
    ruleId: 'interaction-surface/all-shapes-covered',
    status: 'pass',
    reason: 'Every core interaction shape is consumed by the one window (healthy).',
  };
}

/**
 * Verdict (FE backstop): a core interaction shape registers a typed view factory to a tag OTHER than
 * the canonical one-window tag — a reintroduced separate per-shape view (the AskView/AgentView class
 * that P2/P3 retired). Every interaction shape's FE view must be the one window; a second view tag
 * is the FE-tier instance of the same fork class.
 */
export function verdictForSecondInteractionView({ violations, canonicalMountTag }) {
  if (violations.length > 0) {
    return {
      ruleId: 'interaction-surface/second-interaction-view',
      status: 'fail',
      reason:
        `These core interaction shapes register a view factory to a tag other than the one window ` +
        `(${canonicalMountTag}): ${violations.join('; ')}. A second per-shape view reintroduces the ` +
        `561 surface-tier fork — point the shape's registerViewFactory at ${canonicalMountTag} (the ` +
        `one window presets its affordance from the shape-id), do not add a separate view.`,
    };
  }
  return {
    ruleId: 'interaction-surface/no-second-interaction-view',
    status: 'pass',
    reason: `Every core interaction shape's FE view is the one window (${canonicalMountTag}) (healthy).`,
  };
}
