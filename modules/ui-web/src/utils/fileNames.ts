// SPDX-License-Identifier: Apache-2.0
/**
 * Extract just the filename from a path or title.
 * Shared by InspectorContext (citation labels, document names) and InspectorAIInput (placeholder).
 */
export const getFileName = (pathOrTitle: string): string => {
  if (!pathOrTitle) return 'file';
  // If it's just a numeric ID (no path separators), return a friendly label
  if (/^\d+$/.test(pathOrTitle)) return `Document ${pathOrTitle}`;
  // Get last segment after / or \
  const segments = pathOrTitle.split(/[/\\]/);
  const name = segments[segments.length - 1] || pathOrTitle;
  return name;
};
