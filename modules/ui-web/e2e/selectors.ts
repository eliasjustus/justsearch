/**
 * Shared test-id registry for Playwright e2e specs.
 *
 * Keep additions narrow and driven by repeated use across specs.
 */
export const E2E_TEST_IDS = Object.freeze({
  actionOpen: 'action-open',
  actionPanel: 'action-panel',
  actionPanelBackdrop: 'action-panel-backdrop',
  actionPanelInput: 'action-panel-input',
  actionPanelToggleUnavailable: 'action-panel-toggle-unavailable',
  actionReindex: 'action-reindex',
  actionReveal: 'action-reveal',
  actionSummarize: 'action-summarize',
  activityBrain: 'activity-brain',
  activityBrowse: 'activity-browse',
  activityHealth: 'activity-health',
  activityLibrary: 'activity-library',
  activitySearch: 'activity-search',
  activitySettings: 'activity-settings',
  browseFileRow: 'browse-file-row',
  browseFolderRow: 'browse-folder-row',
  brainSwitchToAdvanced: 'brain-switch-to-advanced',
  citation0: 'citation-0',
  citationHighlight: 'citation-highlight',
  citationWarning: 'citation-warning',
  chunkVectorStatusCard: 'chunk-vector-status-card',
  contextActionReveal: 'context-action-reveal',
  contextMenu: 'context-menu',
  embeddingCompatCard: 'embedding-compat-card',
  forceRebuildIndexBtn: 'force-rebuild-index-btn',
  filtersToggle: 'filters-toggle',
  globalCommandChrome: 'global-command-chrome',
  installAiBtn: 'install-ai-btn',
  contextIndicatorAdvanced: 'context-indicator-advanced',
  contextIndicatorSimple: 'context-indicator-simple',
  contextStatePill: 'context-state-pill',
  inspectorAnswer: 'inspector-answer',
  inspectorPreview: 'inspector-preview',
  inspectorSummarize: 'inspector-summarize',
  cancelInstallBtn: 'cancel-install-btn',
  launchpadQueryChip: 'launchpad-query-chip',
  launchpadSetup: 'launchpad-setup',
  matchReasonPill: 'match-reason-pill',
  matchTerms: 'match-terms',
  excludePatternsTextarea: 'exclude-patterns-textarea',
  applyExcludesButton: 'apply-excludes-button',
  mimeBaseToggleApplication: 'mime-base-toggle-application',
  mimeBaseToggleImage: 'mime-base-toggle-image',
  mimeBaseToggleText: 'mime-base-toggle-text',
  repairAiBtn: 'repair-ai-btn',
  rowActionReveal: 'row-action-reveal',
  searchActionsButton: 'search-actions-button',
  searchInput: 'search-input',
  searchSidePanel: 'search-side-panel',
  searchResultRow: 'search-result-row',
  skeletonLibrary: 'skeleton-library',
  trustNudge: 'trust-nudge',
  trustNudgeDismiss: 'trust-nudge-dismiss',
  vduProvenanceBadge: 'vdu-provenance-badge',
});

export const E2E_ACTIVITY_TEST_IDS = Object.freeze({
  search: E2E_TEST_IDS.activitySearch,
  library: E2E_TEST_IDS.activityLibrary,
  browse: E2E_TEST_IDS.activityBrowse,
  brain: E2E_TEST_IDS.activityBrain,
  health: E2E_TEST_IDS.activityHealth,
  settings: E2E_TEST_IDS.activitySettings,
});

export type E2EActivityView = keyof typeof E2E_ACTIVITY_TEST_IDS;

export const E2E_SELECTORS = Object.freeze({
  browseFileRow: `[data-testid="${E2E_TEST_IDS.browseFileRow}"]`,
  browseFolderRow: `[data-testid="${E2E_TEST_IDS.browseFolderRow}"]`,
  searchResultRow: `[data-testid="${E2E_TEST_IDS.searchResultRow}"]`,
});
