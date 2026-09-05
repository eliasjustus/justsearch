import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { stripComments } from '../../lib/strip-comments.mjs';

const norm = (path) => path.replace(/\\/g, '/');

function javaFilesUnder(dir, acc) {
  if (!existsSync(dir)) return acc;
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    const stat = statSync(path);
    if (stat.isDirectory()) javaFilesUnder(path, acc);
    else if (/\.java$/.test(name)) acc.add(path);
  }
  return acc;
}

export function scanAuthoredWordLists({ scopes, minEntries, naturalLanguageWordPattern }) {
  const collectionRe = /\b(?:java\.util\.)?(?:Set|List)\.of\s*\(([\s\S]*?)\)/g;
  const stringLiteralRe = /"((?:\\.|[^"\\])*)"/g;
  const naturalLanguageWordRe = new RegExp(naturalLanguageWordPattern, 'u');
  const findings = [];
  const files = new Set();

  for (const scope of scopes) javaFilesUnder(scope, files);
  for (const file of files) {
    const source = stripComments(readFileSync(file, 'utf8'), { withHtml: false });
    for (const collection of source.matchAll(collectionRe)) {
      const values = [...collection[1].matchAll(stringLiteralRe)].map((match) => match[1]);
      const words = values.filter((value) => naturalLanguageWordRe.test(value));
      if (values.length >= minEntries && words.length === values.length) {
        findings.push(
          `${norm(file)}: authored ${collection[0].slice(0, 24)}… natural-language word list ` +
            `(${values.length} entries) in the query path — derive language-neutral routing from ` +
            `the index's own term statistics instead (581/ADR-0043).`,
        );
      }
    }
  }
  return findings;
}
