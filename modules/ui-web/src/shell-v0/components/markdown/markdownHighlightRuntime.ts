// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 846 §2.4 — the lazily-loaded half of syntax highlighting.
 *
 * NOTHING in this module belongs in the main bundle: it is reached ONLY through the dynamic
 * `import()` in `markdownHighlight.ts`, so the `hljs` core and the grammars below land in their own
 * Vite chunk that the app fetches from its own origin the first time a settled answer or a rendered
 * file actually contains a fenced code block. That keeps the shipped `script-src 'self'` CSP
 * satisfied and the app fully offline — the same shape `PluginLoader`'s lazy `import('ses')` uses.
 *
 * `highlight.js/lib/core` is the registry-only entry point: it ships no grammar, so the bundle cost
 * is exactly the list below. Adding a language is one import plus one `registerLanguage` line; the
 * set is what a local-first desktop search app's answers and indexed repositories actually contain,
 * not the library's full catalogue (~190 grammars).
 */
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import c from 'highlight.js/lib/languages/c';
import cpp from 'highlight.js/lib/languages/cpp';
import csharp from 'highlight.js/lib/languages/csharp';
import cssLang from 'highlight.js/lib/languages/css';
import diff from 'highlight.js/lib/languages/diff';
import go from 'highlight.js/lib/languages/go';
import ini from 'highlight.js/lib/languages/ini';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import markdown from 'highlight.js/lib/languages/markdown';
import python from 'highlight.js/lib/languages/python';
import rust from 'highlight.js/lib/languages/rust';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';
import type { HLJSApi } from 'highlight.js';

/**
 * The registered set, by canonical name. Each grammar carries its own aliases (`js`, `ts`, `sh`,
 * `yml`, `html`, `toml`, `c++`, `cs`, …), which is why the lookup in `markdownHighlight.ts` asks
 * `hljs.getLanguage` rather than matching this list textually.
 */
const LANGUAGES: ReadonlyArray<readonly [string, Parameters<HLJSApi['registerLanguage']>[1]]> = [
  ['bash', bash],
  ['c', c],
  ['cpp', cpp],
  ['csharp', csharp],
  ['css', cssLang],
  ['diff', diff],
  ['go', go],
  ['ini', ini],
  ['java', java],
  ['javascript', javascript],
  ['json', json],
  ['kotlin', kotlin],
  ['markdown', markdown],
  ['python', python],
  ['rust', rust],
  ['sql', sql],
  ['typescript', typescript],
  ['xml', xml],
  ['yaml', yaml],
];

/** The registered grammar names, in registration order (the loader re-exports this for tests). */
export const REGISTERED_LANGUAGES: readonly string[] = LANGUAGES.map(([name]) => name);

let configured = false;

/** Register the grammars once and hand back the configured `hljs` instance. */
export function createHighlighter(): HLJSApi {
  if (!configured) {
    for (const [name, grammar] of LANGUAGES) hljs.registerLanguage(name, grammar);
    // No auto-detection anywhere in this app: a block whose fence names no language stays plain
    // rather than being guessed at (§2.4 — the fallback is an absence, not a branch).
    hljs.configure({ classPrefix: 'hljs-', ignoreUnescapedHTML: true });
    configured = true;
  }
  return hljs;
}
