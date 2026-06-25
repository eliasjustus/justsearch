// SPDX-License-Identifier: Apache-2.0
/**
 * Test-only mock PluginHostApi (nested sub-interfaces per tempdoc 508
 * §2.2). Used by surface unit tests that instantiate Lit elements
 * without the full Shell mounting pipeline.
 */

import type { PluginHostApi } from './plugin-types.js';

export function createMockHostApi(overrides?: {
  registration?: Partial<PluginHostApi['registration']>;
  data?: Partial<PluginHostApi['data']>;
  navigation?: Partial<PluginHostApi['navigation']>;
  ui?: Partial<PluginHostApi['ui']>;
  discovery?: Partial<PluginHostApi['discovery']>;
  settings?: Partial<PluginHostApi['settings']>;
  search?: Partial<PluginHostApi['search']>;
  inspector?: Partial<PluginHostApi['inspector']>;
  selection?: Partial<PluginHostApi['selection']>;
  theme?: Partial<PluginHostApi['theme']>;
  layout?: Partial<PluginHostApi['layout']>;
  platform?: Partial<PluginHostApi['platform']>;
  utilities?: Partial<PluginHostApi['utilities']>;
  ai?: Partial<PluginHostApi['ai']>;
}): PluginHostApi {
  return {
    installedTagNamespace: 'test',
    pluginId: 'test',
    trustTier: 'CORE',
    registration: {
      registerSurfacePort: () => {},
      registerCommand: () => {},
      registerKeybinding: () => {},
      ...overrides?.registration,
    },
    data: {
      fetch: () => Promise.resolve(new Response('{}', { status: 200 })),
      invokeOperation: () => Promise.resolve({ success: true }),
      subscribeResource: () => () => {},
      subscribeHealth: () => () => {},
      ...overrides?.data,
    },
    navigation: {
      navigate: () => {},
      navigateBack: () => {},
      navigateForward: () => {},
      ...overrides?.navigation,
    },
    ui: {
      showNotification: () => {},
      showConfirmDialog: () => Promise.resolve(false),
      copyToClipboard: () => Promise.resolve(),
      showInspector: () => {},
      scrollSurfaceTo: () => {},
      ...overrides?.ui,
    },
    discovery: {
      listOperations: () => [],
      listResources: () => [],
      listSurfaces: () => [],
      getSystemStatus: () => Promise.resolve(null),
      ...overrides?.discovery,
    },
    settings: {
      getSetting: () => undefined,
      setSetting: () => {},
      subscribeSetting: () => () => {},
      ...overrides?.settings,
    },
    search: {
      subscribeSearch: () => () => {},
      getSearchState: () => ({ query: '', results: [] as readonly import('./plugin-types.js').SearchHitSnapshot[], totalHits: 0, matchCount: 0, facetsTruncated: false, isSearching: false, processingTimeMs: null, error: null }),
      setQuery: () => {},
      submitQuery: () => {},
      setSearchApiBase: () => {},
      subscribePinnedSearches: () => () => {},
      getPinnedSearches: () => [],
      pinSearch: () => null,
      unpinSearch: () => false,
      isPinned: () => false,
      recordSearchRun: () => {},
      subscribeFilters: () => () => {},
      getFilters: () => ({}),
      setFilterRange: () => {},
      hasActiveFilter: () => false,
      hitToSelectedItem: () => ({ id: '', title: '' }),
      ...overrides?.search,
    },
    inspector: {
      subscribeInspector: () => () => {},
      getInspectorState: () => ({ isOpen: false, selected: null, activeTab: 'preview', ai: { loading: false, text: '', error: null } }),
      ...overrides?.inspector,
    },
    selection: {
      current: () => null,
      subscribe: () => () => {},
      actions: () => [],
      compose: () => false,
      setSelection: () => {},
      clearSelection: () => {},
      ...overrides?.selection,
    },
    theme: {
      subscribeActiveTheme: () => () => {},
      getActiveThemeId: () => null,
      selectTheme: () => Promise.resolve(),
      getTokens: () => [],
      listThemes: () => [],
      exportTheme: () => null,
      previewTokens: () => {},
      ...overrides?.theme,
    },
    layout: {
      subscribeUserConfig: () => () => {},
      getUserConfig: () => ({ version: 1 }),
      setSurfaceVisibility: () => {},
      setSurfaceOrder: () => {},
      clearAllLayoutOverrides: () => {},
      setActiveLayoutId: () => {},
      onSurfaceCatalogChange: () => () => {},
      ...overrides?.layout,
    },
    platform: {
      capabilities: new Set(),
      pickFile: () => Promise.resolve(null),
      pickFolder: () => Promise.resolve(null),
      revealInExplorer: () => Promise.resolve(),
      openExternal: () => Promise.resolve(),
      ...overrides?.platform,
    },
    utilities: {
      formatRelativeTime: () => '',
      ...overrides?.utilities,
    },
    ai: {
      invokeShape: async () => ({ text: '', events: [] }),
      // eslint-disable-next-line require-yield
      streamShape: async function* () {},
      openSession: () => ({
        id: 'test-session',
        // eslint-disable-next-line require-yield
        send: async function* () {},
        close: () => {},
      }),
      getSessionTranscript: async (sessionId: string) => ({ messages: [], sessionId }),
      getSessionMetadata: async (sessionId: string) => ({ sessionId }),
      ...overrides?.ai,
    },
  };
}
