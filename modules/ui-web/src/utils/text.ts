// SPDX-License-Identifier: Apache-2.0
export const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

export const escapeHTML = (value = "") =>
  value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export const stripHTML = (value = "") => value.replace(/<[^>]+>/g, " ").trim();

export const cleanText = (value = "") => stripHTML(value).replace(/\s+/g, " ").trim();

export const isGeneratedTitle = (value = "") => /^result\s+\d+$/i.test(cleanText(value));
