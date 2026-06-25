/**
 * Rule descriptions for the interaction-surface gate (tempdoc 561 surface tier).
 * Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const INTERACTION_SURFACE_RULE_DESCRIPTIONS = {
  'interaction-surface/register-missing':
    'governance/interaction-surfaces.v1.json (the interaction-surface register) was not found at ' +
    'its configured path.',
  'interaction-surface/source-missing':
    'A scanned authority source (the surface catalog CoreSurfaceCatalog.java or the shape catalog ' +
    'CoreConversationShapeCatalog.java) was not found at its register-declared path. Fix the path ' +
    'in governance/interaction-surfaces.v1.json scan.{surfaceCatalog,shapeCatalog}.',
  'interaction-surface/core-set-unreadable':
    'The core interaction-shape authority (CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES) ' +
    'parsed empty. The whole gate projects from it — verify the constant is `Set.of("core.x", ...)`.',
  'interaction-surface/fe-mirror-drift':
    'The FE mirror (coreInteractionShapes.ts CORE_INTERACTION_SHAPES) disagrees with the Java ' +
    'authority CORE_USER_INTERACTION_SHAPES. The two must name the same set so the one window\'s ' +
    'interaction modes are declared once — bring the mirror back in sync (no second authority).',
  'interaction-surface/fe-mirror-synced':
    'The FE mirror equals the Java core interaction-shape authority (healthy).',
  'interaction-surface/multiple-visible-interaction-surfaces':
    'More than one VISIBLE (USER-audience, RAIL/STAGE) surface consumes a core interaction shape. ' +
    'The one window must be the sole visible interaction surface; a second is the 561 surface-tier ' +
    'fork class (two interaction authorities, neither subordinate — 548\'s class at the surface ' +
    'tier). Retire or DEEPLINK-demote the extra so every interaction shape routes into one window.',
  'interaction-surface/single-visible-interaction-surface':
    'At most one visible interaction surface consumes the core interaction shapes (healthy).',
  'interaction-surface/canonical-window-missing':
    'The declared canonical one window (register canonicalSurface) is not a visible (USER, ' +
    'RAIL/STAGE) interaction surface — it is missing or a different surface replaced it. Restore ' +
    'the one window, or update canonicalSurface if it was deliberately renamed.',
  'interaction-surface/canonical-window-present':
    'The declared canonical one window is the visible interaction surface (healthy).',
  'interaction-surface/uncovered-core-shape':
    'A core interaction shape is not consumed by the one window — an orphaned interaction mode the ' +
    'single window cannot host. Add it to the canonical surface\'s conversationShapes, or remove it ' +
    'from CORE_USER_INTERACTION_SHAPES if it is no longer a user-facing interaction mode.',
  'interaction-surface/all-shapes-covered':
    'Every core interaction shape is consumed by the one window (healthy).',
  'interaction-surface/second-interaction-view':
    'A core interaction shape registers a typed FE view factory to a tag other than the one-window ' +
    'tag — a reintroduced separate per-shape view (the AskView/AgentView class P2/P3 retired). ' +
    'Point the shape\'s registerViewFactory at the one window (it presets its affordance from the ' +
    'shape-id); do not add a second view.',
  'interaction-surface/no-second-interaction-view':
    'Every core interaction shape\'s FE view is the one window (healthy).',
  'interaction-surface/vacuous-scan':
    'Fewer core interaction shapes were parsed from the shape catalog than scan.expectedMinPopulation — ' +
    'a parser-breaking catalog refactor would otherwise let the coverage checks pass on a near-empty set ' +
    '(the §5 vacuous-pass floor; size-0 is the separate empty-core-set hard-stop). Fix the catalog/parser ' +
    'or lower scan.expectedMinPopulation.',
  'interaction-surface/scan-population-live':
    'The shape-catalog parse yielded >= the declared floor of core interaction shapes — not vacuous (healthy).',
};
