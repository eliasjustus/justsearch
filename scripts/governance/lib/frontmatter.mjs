/**
 * Minimal YAML-frontmatter parser — no external dependency.
 *
 * Format: opening '---', key:value lines, closing '---', then markdown body.
 *
 * Originally factored out of `scripts/contract-governance/lib/changeset-parser.mjs`
 * so multiple gate classes can share frontmatter-driven changeset authoring
 * without re-implementing parsing (tempdoc 530).
 *
 * Returns `null` when no frontmatter block is present (the file is treated as
 * documentation, not a declaration).
 *
 * @param {string} content
 * @returns {{frontmatter: Record<string, string>, body: string} | null}
 */
export function parseFrontmatter(content) {
  const lines = content.split(/\r?\n/);
  if (lines[0]?.trim() !== '---') return null;
  const fmEnd = lines.findIndex((l, i) => i > 0 && l.trim() === '---');
  if (fmEnd === -1) return null;
  const fmLines = lines.slice(1, fmEnd);
  const frontmatter = {};
  for (const fl of fmLines) {
    const m = fl.match(/^([a-zA-Z][\w-]*)\s*:\s*(.*)$/);
    if (!m) continue;
    frontmatter[m[1]] = m[2].trim();
  }
  const body = lines.slice(fmEnd + 1).join('\n');
  return { frontmatter, body };
}
