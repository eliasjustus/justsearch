/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stage 1 deterministic normalization for entity disambiguation.
 *
 * <p>Transforms raw entity mentions into a canonical surface form before fuzzy matching.
 * Operations: Unicode NFKC, case fold, punctuation strip, PERSON comma reorder,
 * honorific/suffix removal, ORGANIZATION article strip, whitespace collapse.
 */
public final class EntityNormalizer {
  private EntityNormalizer() {}

  /** Entity type discriminator for type-specific normalization rules. */
  public enum EntityType {
    PERSON,
    ORGANIZATION,
    LOCATION
  }

  // Honorific prefixes to strip from PERSON entities (lowercase, no periods).
  static final Set<String> HONORIFIC_PREFIXES =
      Set.of(
          "mr", "mrs", "ms", "miss", "mx", "mme", "mlle",
          "dr", "prof", "rev", "hon", "esq",
          "gen", "col", "maj", "capt", "cpt", "lt", "sgt", "cpl", "pvt",
          "adm", "cmdr", "cdr",
          "fr", "br", "msgr", "abp", "bp", "sr",
          "amb", "gov", "pres", "rep", "sen",
          "sir", "dame", "lord", "lady");

  // Suffixes to strip from PERSON entities (lowercase, no periods).
  static final Set<String> HONORIFIC_SUFFIXES =
      Set.of(
          "jr", "sr", "ii", "iii", "iv", "v",
          "phd", "md", "dds", "jd", "edd", "dmin", "dba",
          "rn", "esq", "cpa", "pe",
          "mba", "msc", "bsc", "ba", "ma", "mphil",
          "ret", "usn", "usa", "usaf", "usmc");

  // Leading articles to strip from ORGANIZATION entities.
  private static final Set<String> LEADING_ARTICLES = Set.of("the", "a", "an");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /**
   * Normalizes a raw entity mention for disambiguation comparison.
   *
   * @param rawForm the raw entity text from NER extraction
   * @param type the entity type (determines which rules apply)
   * @return normalized form, or the original lowercased if no rules apply
   */
  public static String normalize(String rawForm, EntityType type) {
    if (rawForm == null || rawForm.isBlank()) {
      return "";
    }

    // 1. Unicode NFKC normalization (compatibility decomposition + canonical composition)
    String s = Normalizer.normalize(rawForm, Normalizer.Form.NFKC);

    // 2. Case fold to lowercase
    s = s.toLowerCase(Locale.ROOT);

    // 3. Strip periods (before tokenization, so "Dr." becomes "Dr" for lookup)
    s = s.replace(".", "");

    // 4. PERSON: single-comma reorder ("Smith, John" → "John Smith")
    if (type == EntityType.PERSON) {
      s = reorderCommaName(s);
    }

    // 5. Remove remaining commas
    s = s.replace(",", " ");

    // 6. Collapse whitespace
    s = WHITESPACE.matcher(s.trim()).replaceAll(" ");

    // 7. Type-specific token stripping
    if (type == EntityType.PERSON) {
      s = stripHonorifics(s);
    } else if (type == EntityType.ORGANIZATION) {
      s = stripLeadingArticle(s);
    }

    return s.trim();
  }

  /**
   * Tokenizes a normalized form into lowercase tokens for SoftTFIDF.
   *
   * @param normalized a string already processed by {@link #normalize}
   * @return list of non-empty tokens
   */
  public static List<String> tokenize(String normalized) {
    if (normalized == null || normalized.isBlank()) {
      return List.of();
    }
    String[] parts = WHITESPACE.split(normalized.trim());
    List<String> tokens = new ArrayList<>(parts.length);
    for (String part : parts) {
      if (!part.isEmpty()) {
        tokens.add(part);
      }
    }
    return tokens;
  }

  /** Reorder "Last, First" to "First Last" for single-comma PERSON names. */
  private static String reorderCommaName(String s) {
    int commaIdx = s.indexOf(',');
    if (commaIdx < 0) {
      return s; // no comma
    }
    // Only reorder if exactly one comma
    if (s.indexOf(',', commaIdx + 1) >= 0) {
      return s; // multiple commas — too ambiguous
    }
    String before = s.substring(0, commaIdx).trim();
    String after = s.substring(commaIdx + 1).trim();
    if (before.isEmpty() || after.isEmpty()) {
      return s;
    }
    return after + " " + before;
  }

  /** Strip honorific prefixes (left-to-right) and suffixes (right-to-left), keeping >= 1 token. */
  private static String stripHonorifics(String s) {
    List<String> tokens = new ArrayList<>(List.of(WHITESPACE.split(s)));
    if (tokens.size() <= 1) {
      return s;
    }

    // Strip prefixes left-to-right
    while (tokens.size() > 1 && HONORIFIC_PREFIXES.contains(tokens.get(0))) {
      tokens.remove(0);
    }

    // Strip suffixes right-to-left
    while (tokens.size() > 1 && HONORIFIC_SUFFIXES.contains(tokens.get(tokens.size() - 1))) {
      tokens.remove(tokens.size() - 1);
    }

    return String.join(" ", tokens);
  }

  /** Strip leading article ("The Acme Company" → "Acme Company"). */
  private static String stripLeadingArticle(String s) {
    int spaceIdx = s.indexOf(' ');
    if (spaceIdx <= 0) {
      return s;
    }
    String first = s.substring(0, spaceIdx);
    if (LEADING_ARTICLES.contains(first)) {
      return s.substring(spaceIdx + 1);
    }
    return s;
  }
}
