import type { KnipConfig } from "knip";

const config: KnipConfig = {
  entry: [
    "src/mocks/**/*.ts",
    "scripts/dev-all.cjs",
    "scripts/visual-regression.cjs",
    "scripts/lib/*.{cjs,mjs}",
  ],
  project: [
    "src/**/*.{ts,tsx,jsx}",
    "scripts/**/*.{mjs,cjs,ts}",
  ],
  ignoreDependencies: [
    "tailwindcss", // used as PostCSS plugin via @tailwindcss/postcss, not direct import
  ],
};

export default config;
