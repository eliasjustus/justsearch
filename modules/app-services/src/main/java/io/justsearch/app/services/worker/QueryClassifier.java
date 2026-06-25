/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.QueryType;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based pre-retrieval query classification (306: Stage A, tempdoc 270 Layer 1).
 *
 * <p>Classifies queries into {@link QueryType} using syntactic signals only — no model
 * inference, no index statistics. Runs on the Head side in &lt;1 ms.
 *
 * <p>Misclassification cost is low: a navigational query classified as informational still
 * returns correct results (just with unnecessary CE/expansion overhead). An informational
 * query classified as navigational skips CE but first-stage fusion is usually sufficient.
 */
final class QueryClassifier {

  private QueryClassifier() {}

  private static final Set<String> FILE_EXTENSIONS = Set.of(
      ".pdf", ".docx", ".xlsx", ".pptx", ".txt", ".md", ".csv", ".json", ".xml",
      ".html", ".htm", ".java", ".py", ".js", ".ts", ".c", ".cpp", ".h", ".rs",
      ".go", ".rb", ".sh", ".bat", ".ps1", ".yaml", ".yml", ".toml", ".ini",
      ".cfg", ".log", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".mp3", ".mp4",
      ".zip", ".tar", ".gz", ".doc", ".xls", ".ppt", ".rtf", ".odt", ".ods");

  private static final Set<String> QUESTION_WORDS = Set.of(
      "how", "what", "why", "where", "when", "which", "who", "whom", "whose",
      "does", "do", "is", "are", "can", "could", "should", "would", "will");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern CAMEL_CASE = Pattern.compile(".*[a-z][A-Z].*");
  private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");

  /**
   * Classifies a query into a {@link QueryType} using priority-ordered syntactic rules.
   *
   * <p>Rules (from tempdoc 270 lines 355-366, adapted for Head-side):
   * <ol>
   *   <li>Token with file extension (e.g., report.pdf) → NAVIGATIONAL</li>
   *   <li>Multi-segment path (e.g., src/main/java) or backslash → NAVIGATIONAL</li>
   *   <li>Entire query is "quoted" → EXACT_MATCH</li>
   *   <li>Starts with question word and &gt;3 terms → INFORMATIONAL</li>
   *   <li>Single identifier-like term (CamelCase, snake_case) → NAVIGATIONAL</li>
   *   <li>Single plain term → EXPLORATORY</li>
   *   <li>Default → INFORMATIONAL</li>
   * </ol>
   */
  static QueryType classify(String query) {
    if (query == null || query.isBlank()) {
      return QueryType.INFORMATIONAL;
    }

    String trimmed = query.strip();

    if (hasFileExtension(trimmed)) {
      return QueryType.NAVIGATIONAL;
    }
    if (hasPathFragment(trimmed)) {
      return QueryType.NAVIGATIONAL;
    }
    if (isQuotedPhrase(trimmed)) {
      return QueryType.EXACT_MATCH;
    }

    String[] terms = WHITESPACE.split(trimmed);
    if (hasQuestionWord(terms) && terms.length > 3) {
      return QueryType.INFORMATIONAL;
    }
    if (terms.length == 1) {
      if (looksLikeIdentifier(terms[0])) {
        return QueryType.NAVIGATIONAL;
      }
      return QueryType.EXPLORATORY;
    }

    return QueryType.INFORMATIONAL;
  }

  /**
   * Checks if any whitespace-delimited token looks like a filename with a recognized extension.
   * Requires at least one character before the dot to avoid matching bare extensions like ".py".
   */
  static boolean hasFileExtension(String query) {
    String[] tokens = WHITESPACE.split(query.strip());
    for (String token : tokens) {
      String lower = token.toLowerCase(Locale.ROOT);
      int dot = lower.lastIndexOf('.');
      if (dot > 0) { // must have chars before the dot — not a bare ".py"
        String ext = lower.substring(dot);
        if (FILE_EXTENSIONS.contains(ext)) return true;
      }
    }
    return false;
  }

  /**
   * Checks if the query contains a path-like token. Requires either:
   * <ul>
   *   <li>A backslash (almost never natural language)</li>
   *   <li>A token starting with ./ or ../ (relative path)</li>
   *   <li>A token with 2+ forward slashes (multi-segment path like src/main/java)</li>
   * </ul>
   * Single forward slashes (e.g., "pros/cons", "and/or") are NOT treated as paths.
   */
  static boolean hasPathFragment(String query) {
    String[] tokens = WHITESPACE.split(query.strip());
    for (String token : tokens) {
      if (token.contains("\\")) return true;
      if (token.startsWith("./") || token.startsWith("../")) return true;
      long slashCount = token.chars().filter(c -> c == '/').count();
      if (slashCount >= 2) return true;
    }
    return false;
  }

  /** Checks if the entire query is a quoted phrase ("..."). */
  static boolean isQuotedPhrase(String query) {
    return query.length() >= 3
        && query.charAt(0) == '"'
        && query.charAt(query.length() - 1) == '"';
  }

  /** Checks if the first term is a question/interrogative word. */
  static boolean hasQuestionWord(String[] terms) {
    if (terms.length == 0) return false;
    return QUESTION_WORDS.contains(terms[0].toLowerCase(Locale.ROOT));
  }

  /**
   * Checks if a single term looks like a code identifier rather than a natural language word.
   * Detects CamelCase, snake_case, and kebab-case-with-digits patterns.
   */
  static boolean looksLikeIdentifier(String term) {
    if (term.length() < 2) return false;
    // CamelCase: lowercase followed by uppercase (e.g., ResolvedConfigBuilder)
    if (CAMEL_CASE.matcher(term).matches()) return true;
    // snake_case: contains underscore (e.g., worker_config_snapshot)
    if (term.contains("_")) return true;
    // kebab-case with digits (e.g., cuda-12, v1.24.3)
    if (term.contains("-") && HAS_DIGIT.matcher(term).matches()) return true;
    return false;
  }
}
