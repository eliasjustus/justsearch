/*
 * Focus Mode — V1.5 composed custom UI demonstration.
 *
 * Activates a complete user-authored UI configuration:
 *   - Theme:   sepia-focus (warm reading palette)
 *   - Layout:  search-first, only Search + Library + (loaded plugins) visible
 *   - Plugin:  scratch-notes (real localStorage-backed notepad)
 *
 * This file is loadable directly in the browser (no build step).
 * Boot the dev stack, then in the console:
 *
 *   const { activateFocusMode } = await import('/examples/custom-ui-focus/activate.js');
 *   await activateFocusMode();
 *
 * Or to revert:
 *   const { deactivateFocusMode } = await import('/examples/custom-ui-focus/activate.js');
 *   await deactivateFocusMode();
 *
 * The whole composition exercises slices 465 + 471 + 472 + 474 in
 * one user-facing flow.
 */

// V1.5.1 / SES note: the Vite dev server's lockdown() rejects
// `import()` inside eval'd console code (correct SES security
// behavior). This file runs as a compiled <script type="module">,
// so its `import()` calls are allowed. We expose the public API on
// globalThis so eval'd code can call activate / deactivate without
// triggering the eval-import rejection.
globalThis.__focusMode = {
  activate: () => activateFocusMode(),
  deactivate: () => deactivateFocusMode(),
};

export async function activateFocusMode() {
  const themeMod = await import('/src/shell-v0/state/themeState.ts');
  const userCfg = await import('/src/shell-v0/state/userConfigState.ts');
  const pluginApi = await import('/src/shell-v0/plugin-api/index.ts');
  const catalog = await import('/src/api/registry/SurfaceCatalogClient.ts');

  // 1. Theme — sepia warm palette.
  await themeMod.loadAndApplyTheme('sepia-focus');

  // 2. Plugin — scratch notes with real localStorage persistence.
  //    Reuses a singleton registry stashed on globalThis so repeated
  //    activations don't double-install.
  const registry =
    globalThis.__focusModeRegistry ??
    (globalThis.__focusModeRegistry = new pluginApi.PluginRegistry());

  if (!registry.has('scratch-notes')) {
    // Fetch the plugin source as text from /public/, then re-emit
    // as a data: URL for the loader. Vite dev server adds a `?import`
    // query to dynamic-import URLs that breaks for `public/` static
    // files (returns 500). data: URLs are not subject to Vite
    // middleware, so the loader's native `import(dataUrl)` works
    // cleanly. Production V1.5.1 plugins live under Tauri filesystem
    // paths, not Vite — this is a dev-mode-only workaround.
    const res = await fetch('/examples/plugin-scratch-notes/plugin.js');
    if (!res.ok) {
      throw new Error('plugin fetch failed: ' + res.status);
    }
    const src = await res.text();
    // UTF-8-safe base64: btoa() rejects non-Latin1 bytes (the plugin
    // source contains em-dashes etc.). Encode to bytes first, then
    // base64-encode the byte string.
    const bytes = new TextEncoder().encode(src);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    const dataUrl = 'data:text/javascript;base64,' + btoa(bin);
    await pluginApi.loadPluginFromUrl(registry, dataUrl);
    catalog.mergePluginSurfaceContributions(registry.surfaceContributions());
  }

  // 3. Layout — search-first, hide everything except Search + Library +
  //    the scratch-notes plugin contribution.
  userCfg.setSurfaceVisibility('core.help-surface', false);
  userCfg.setSurfaceVisibility('core.settings-surface', false);
  userCfg.setSurfaceVisibility('core.brain-surface', false);
  userCfg.setSurfaceVisibility('core.agent-surface', false);
  userCfg.setSurfaceVisibility('core.browse-surface', false);
  userCfg.setSurfaceVisibility('core.health-surface', false);
  userCfg.setSurfaceOrder([
    'core.search-surface',
    'core.library-surface',
    'scratch-notes.pad',
  ]);

  return {
    activeTheme: themeMod.getActiveThemeId(),
    pluginInstalled: registry.has('scratch-notes'),
    pluginSurfaces: registry.surfaceContributions().map((s) => s.contribution.id),
    userConfig: userCfg.getUserConfig(),
  };
}

export async function deactivateFocusMode() {
  const themeMod = await import('/src/shell-v0/state/themeState.ts');
  const userCfg = await import('/src/shell-v0/state/userConfigState.ts');
  const catalog = await import('/src/api/registry/SurfaceCatalogClient.ts');

  themeMod.clearActiveTheme();
  userCfg.clearAllLayoutOverrides();
  userCfg.clearAllSurfaceOverrides();

  const registry = globalThis.__focusModeRegistry;
  if (registry?.has('scratch-notes')) {
    registry.uninstall('scratch-notes');
    catalog.removePluginSurfaceContributions('scratch-notes');
  }

  return { reverted: true };
}
