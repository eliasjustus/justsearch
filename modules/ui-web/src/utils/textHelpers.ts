// SPDX-License-Identifier: Apache-2.0
/**
 * Strip HTML tags from a string (used for snippet display in preview fallback).
 */
export const stripHtml = (value: string): string => {
  if (!value) return "";
  return value.replace(/<[^>]*>/g, "");
};

/**
 * Extract YAML frontmatter from markdown content.
 * Returns the parsed key-value pairs and the remaining content.
 */
export const extractFrontmatter = (text: string): { frontmatter: Record<string, string> | null; content: string } => {
  const match = text.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
  if (!match) return { frontmatter: null, content: text };
  const yaml = match[1] ?? '';
  const content = match[2] ?? '';
  const frontmatter: Record<string, string> = {};
  yaml.split(/\r?\n/).forEach(line => {
    const colonIndex = line.indexOf(':');
    if (colonIndex > 0) {
      const key = line.slice(0, colonIndex).trim();
      const value = line.slice(colonIndex + 1).trim();
      if (key && value) frontmatter[key] = value;
    }
  });
  return { frontmatter: Object.keys(frontmatter).length > 0 ? frontmatter : null, content };
};
