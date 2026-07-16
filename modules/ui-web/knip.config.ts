import type { KnipConfig } from "knip";

const config: KnipConfig = {
  entry: [
    "src/mocks/**/*.ts",
    "scripts/dev-all.cjs",
    "scripts/visual-regression.cjs",
    "scripts/evidence/**/*.mjs",
    "scripts/lib/*.{cjs,mjs}",
  ],
  project: [
    "src/**/*.{ts,tsx,jsx}",
    "scripts/**/*.{mjs,cjs,ts}",
  ],
  ignoreDependencies: [
    "tailwindcss", // used as PostCSS plugin via @tailwindcss/postcss, not direct import
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
