// SPDX-License-Identifier: Apache-2.0
/**
 * Browser-native Intl API utilities for locale-aware formatting.
 * Phase 0 of i18n — zero dependencies, uses Intl.PluralRules,
 * Intl.NumberFormat, and Intl.RelativeTimeFormat.
 */

// Lazy-initialized formatters (safe for test environments where navigator may not exist)
let _pluralRules: Intl.PluralRules | undefined;
let _relativeFormatter: Intl.RelativeTimeFormat | undefined;

const getPluralRules = (locale?: string): Intl.PluralRules => {
  if (!locale && _pluralRules) return _pluralRules;
  const rules = new Intl.PluralRules(locale ?? navigator.language);
  if (!locale) _pluralRules = rules;
  return rules;
};

const getRelativeFormatter = (locale?: string): Intl.RelativeTimeFormat => {
  if (!locale && _relativeFormatter) return _relativeFormatter;
  const fmt = new Intl.RelativeTimeFormat(locale ?? navigator.language, {
    style: 'narrow',
    numeric: 'always',
  });
  if (!locale) _relativeFormatter = fmt;
  return fmt;
};

/**
 * Returns the correctly pluralized noun form for the given count.
 * Does NOT include the count in the output — callers format the count themselves.
 *
 * Uses Intl.PluralRules so it respects locale plural categories
 * (e.g. Russian "one"/"few"/"many", Arabic has 6 forms).
 *
 * @param count - The number to pluralize for
 * @param singular - The singular noun form ("file", "match", "result")
 * @param plural - Optional explicit plural form. Defaults to singular + 's'.
 * @param locale - Optional locale override (defaults to navigator.language)
 */
export const pluralize = (
  count: number,
  singular: string,
  plural?: string,
  locale?: string,
): string => {
  const rule = getPluralRules(locale).select(count);
  if (rule === 'one') return singular;
  return plural ?? singular + 's';
};

/**
 * Format a byte count for display using locale-aware number formatting.
 * Uses Intl.NumberFormat for KB+ units (locale-aware decimal separators and unit names).
 * Falls back to manual formatting for bytes (Intl has no 'byte' unit).
 *
 * @param bytes - Byte count. Negative values supported (prefixed with Unicode minus).
 * @param locale - Optional locale override (defaults to navigator.language)
 */
export const formatBytes = (bytes: number, locale?: string): string => {
  if (bytes === 0) return '0 B';
  const abs = Math.abs(bytes);
  const k = 1024;
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte'] as const;
  const i = Math.min(units.length - 1, Math.floor(Math.log(abs) / Math.log(k)));

  if (i === 0) {
    // "byte" is not a valid Intl.NumberFormat unit
    return (bytes < 0 ? '\u2212' : '') + abs + ' B';
  }

  const val = abs / Math.pow(k, i);
  const formatted = new Intl.NumberFormat(locale ?? navigator.language, {
    style: 'unit',
    unit: units[i],
    unitDisplay: 'short',
    maximumFractionDigits: val >= 10 ? 0 : 1,
  }).format(val);

  return bytes < 0 ? '\u2212' + formatted : formatted;
};

/**
 * Format a millisecond epoch timestamp as a locale-aware relative time string.
 * Uses Intl.RelativeTimeFormat with narrow style.
 *
 * Output examples: "3h ago" (en), "vor 3 Std." (de), "3 時間前" (ja)
 *
 * @param ms - Epoch milliseconds timestamp
 * @param locale - Optional locale override (defaults to navigator.language)
 */
export const formatRelativeTime = (
  ms: number,
  locale?: string,
): string | undefined => {
  if (!Number.isFinite(ms) || ms <= 0) return undefined;
  const diff = Date.now() - ms;
  if (diff < 0) return undefined;

  const fmt = getRelativeFormatter(locale);
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return fmt.format(-sec, 'second');
  const min = Math.floor(sec / 60);
  if (min < 60) return fmt.format(-min, 'minute');
  const hr = Math.floor(min / 60);
  if (hr < 24) return fmt.format(-hr, 'hour');
  const day = Math.floor(hr / 24);
  if (day < 30) return fmt.format(-day, 'day');
  const mo = Math.floor(day / 30.44);
  if (mo < 1) return fmt.format(-day, 'day'); // Edge case: ~30 days rounds to 0 months
  if (mo < 12) return fmt.format(-mo, 'month');
  const yr = Math.floor(mo / 12);
  return fmt.format(-yr, 'year');
};
