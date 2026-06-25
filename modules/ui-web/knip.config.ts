import type { KnipConfig } from "knip";

const config: KnipConfig = {
  entry: [
    "src/mocks/**/*.ts",
    "scripts/dev-all.cjs",
    "scripts/visual-regression.cjs",
    "scripts/evidence/**/*.mjs",
    "scripts/lib/*.{cjs,mjs}",
    "e2e/**/*.ts",
  ],
  project: [
    "src/**/*.{ts,tsx,jsx}",
    "scripts/**/*.{mjs,cjs,ts}",
    "e2e/**/*.ts",
  ],
  ignoreDependencies: [
    "@lingui/cli", // used by lingui extract/compile CLI, not TS imports
    "tailwindcss", // used as PostCSS plugin via @tailwindcss/postcss, not direct import
    "playwright", // re-exported by @playwright/test; evidence scripts import from 'playwright'
  ],
  ignore: [
    // View files need export default for React.lazy() in Stage.tsx
    "src/components/views/LibraryView.tsx",
    "src/components/views/BrowseView.tsx",
    "src/components/views/BrainView.tsx",
    "src/components/views/HealthView.tsx",
    "src/components/views/HelpView.tsx",
    "src/components/views/SettingsView.tsx",
  ],
};

export default config;
