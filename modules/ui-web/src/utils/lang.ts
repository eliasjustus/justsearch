// SPDX-License-Identifier: Apache-2.0
const DEFAULT_LANG = "en";

export const normalizeLanguage = (lang?: string | null) => {
  if (!lang) return DEFAULT_LANG;
  const value = lang.replace("_", "-").trim();
  if (!value) return DEFAULT_LANG;
  return value.toLowerCase();
};
