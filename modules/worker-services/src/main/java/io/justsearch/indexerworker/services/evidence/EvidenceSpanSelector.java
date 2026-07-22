/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.evidence;

import io.justsearch.indexerworker.services.HighlightingOps;
import io.justsearch.ipc.MatchSpan;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Analyzer;

/**
 * Selects the answer-bearing {@link EvidenceSpan}(s) of a document at retrieval time (tempdoc 775
 * §E step 1). One selection authority for the evidence window; delivery projects {@code
 * ExcerptRegion} from the result.
 *
 * <p>It reuses the exact candidate windows the IDF-only delivery path already builds ({@link
 * HighlightingOps#buildClusters}/{@link HighlightingOps#projectWindow}) — no new full-content
 * re-scan. The one behavioral change vs. the IDF-only path is the ranking: candidate windows are
 * ordered by <b>distinguishing-entity coverage first, then query-term IDF density</b> — so the
 * window carrying the query's rare/entity token beats the densest query-term cluster (the 45%-legal
 * miss is exactly a densest-cluster ≠ entity-sentence divergence; 771 §E item 1b). Reacts only to
 * the query + the document's own content — never corpus identity (D-005).
 *
 * <p>The distinguishing-entity signal is pluggable so both candidates can be measured against the
 * 771 §E offline probe (tempdoc 775 deferred item b): {@link EntitySignal#DF_RARITY} (a window token
 * whose corpus doc-frequency is rare — the df=1 bridge name is the strongest possible) vs {@link
 * EntitySignal#NER_MEMBERSHIP} (a window token that is one of the document's own NER entities).
 */
public final class EvidenceSpanSelector {

  /** Which distinguishing-entity signal ranks the candidate windows. */
  public enum EntitySignal {
    DF_RARITY,
    NER_MEMBERSHIP
  }

  /** Supplies corpus doc-frequencies for the {@link EntitySignal#DF_RARITY} signal. */
  @FunctionalInterface
  public interface DfProvider {
    /** Returns the per-term document frequency over the {@code content} field. */
    Map<String, Integer> docFreqs(Set<String> terms);
  }

  /** A selected span plus its delivery-envelope match spans (offsets relative to the span text). */
  public record SelectedSpan(EvidenceSpan span, List<MatchSpan> matchSpans) {}

  /**
   * Projects a selected span onto the delivery {@code ExcerptRegion} envelope (tempdoc 775 step 1).
   * The interactive delivery consumer of the one selection authority: {@code text}/{@code charStart}/
   * {@code charEnd}/{@code lineStart} map onto the region; the delivery envelope <b>adds</b> the
   * per-window {@code matchSpans}; the remaining {@code EvidenceSpan} fields ({@code parentDocId},
   * {@code lineEnd}, {@code headingText}, {@code selectingLegs}, {@code entityCoverage}) are
   * deliberately dropped here — they serve the RAG/citation + CE envelopes (steps 2/3) and selection
   * provenance, not the interactive excerpt. {@code lineOffset} is the chunk-start line for a chunk
   * hit (0 for a full document), matching the IDF-only delivery path.
   */
  public static io.justsearch.ipc.ExcerptRegion toExcerptRegion(SelectedSpan sel, int lineOffset) {
    EvidenceSpan span = sel.span();
    return io.justsearch.ipc.ExcerptRegion.newBuilder()
        .setText(span.text())
        .setStartChar(span.charStart())
        .setEndChar(span.charEnd())
        .setApproxLine(span.lineStart() + lineOffset)
        .addAllMatchSpans(sel.matchSpans())
        .build();
  }

  // A token candidate for the distinguishing-entity signal: letters/digits, length >= 3.
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]{2,}");

  private final EntitySignal signal;
  private final DfProvider dfProvider;
  private final long numDocs;

  public EvidenceSpanSelector(EntitySignal signal, DfProvider dfProvider, long numDocs) {
    this.signal = signal == null ? EntitySignal.DF_RARITY : signal;
    this.dfProvider = dfProvider;
    this.numDocs = numDocs;
  }

  /**
   * Selects up to {@code maxRegions} non-overlapping evidence spans, answer-bearing-first.
   *
   * @param docEntityText the concatenation of this document's NER entity text fields (used only for
   *     {@link EntitySignal#NER_MEMBERSHIP}); may be empty
   * @param parentDocId parent doc id for the minted spans (empty for a full/chunkless document)
   */
  public List<SelectedSpan> select(
      Analyzer analyzer,
      org.apache.lucene.search.Query query,
      String content,
      int maxRegions,
      Map<String, Double> termIdfWeights,
      String docEntityText,
      String parentDocId) {
    if (analyzer == null || query == null || content == null || content.isEmpty()) return List.of();
    if (maxRegions <= 0) return List.of();

    ArrayList<HighlightingOps.TermMatch> matchOffsets = new ArrayList<>();
    HighlightingOps.collectMatchOffsets(matchOffsets, analyzer, query, content, 200);
    if (matchOffsets.isEmpty()) return List.of();

    List<HighlightingOps.MatchCluster> clusters = HighlightingOps.buildClusters(matchOffsets);
    Map<String, Double> idf = termIdfWeights != null ? termIdfWeights : Map.of();
    int contentLen = content.length();

    BreakIterator sentenceBreaker = BreakIterator.getSentenceInstance(Locale.ROOT);
    sentenceBreaker.setText(content);

    // Project every candidate window once (geometry is shared with the IDF-only delivery path).
    List<Candidate> candidates = new ArrayList<>(clusters.size());
    for (HighlightingOps.MatchCluster c : clusters) {
      HighlightingOps.WindowGeom w = HighlightingOps.projectWindow(content, c, sentenceBreaker);
      String text = content.substring(w.winStart(), w.winEnd());
      double base = HighlightingOps.scoreCluster(c, idf, contentLen);
      candidates.add(new Candidate(w, text, base));
    }

    // Distinguishing-entity coverage per window.
    if (signal == EntitySignal.DF_RARITY) {
      scoreDfRarity(candidates);
    } else {
      scoreNerMembership(candidates, docEntityText);
    }

    // Rank: answer-bearing (entity coverage) first, then query-term IDF density (base) as before.
    candidates.sort(
        (a, b) -> {
          int e = Double.compare(b.entityScore, a.entityScore);
          return e != 0 ? e : Double.compare(b.base, a.base);
        });

    ArrayList<SelectedSpan> selected = new ArrayList<>();
    ArrayList<int[]> takenRanges = new ArrayList<>();
    for (Candidate cand : candidates) {
      if (selected.size() >= maxRegions) break;
      if (overlapsTaken(cand.geom.winStart(), cand.geom.winEnd(), takenRanges)) continue;

      List<MatchSpan> spans =
          HighlightingOps.windowMatchSpans(cand.text, cand.geom.winStart(), matchOffsets);
      int lineEnd = cand.geom.approxLine() + countNewlines(cand.text);
      EvidenceSpan span =
          new EvidenceSpan(
              parentDocId,
              cand.geom.winStart(),
              cand.geom.winEnd(),
              cand.geom.approxLine(),
              lineEnd,
              "",
              cand.text,
              legsIn(cand, matchOffsets),
              List.copyOf(cand.entityCoverage));
      selected.add(new SelectedSpan(span, spans));
      takenRanges.add(new int[] {cand.geom.winStart(), cand.geom.winEnd()});
    }

    // Natural reading order (matches the delivery path).
    selected.sort(java.util.Comparator.comparingInt(s -> s.span().charStart()));
    return List.copyOf(selected);
  }

  // ---- distinguishing-entity signals ----

  /**
   * DF_RARITY: a window's entity score is the max IDF of any window token whose corpus df is rare
   * (df in [1, ~2% of corpus]). The globally-unique bridge name (df=1) yields the maximum possible
   * IDF, so the window carrying it beats windows whose rarest token is more common.
   */
  private void scoreDfRarity(List<Candidate> candidates) {
    if (dfProvider == null || numDocs <= 0) return;
    // Batch one df lookup over the union of all window tokens.
    Set<String> allTokens = new TreeSet<>();
    for (Candidate c : candidates) {
      c.tokens = tokenize(c.text);
      allTokens.addAll(c.tokens);
    }
    if (allTokens.isEmpty()) return;
    Map<String, Integer> df = dfProvider.docFreqs(allTokens);
    int rarityThreshold = Math.max(1, (int) (numDocs * 0.02));
    for (Candidate c : candidates) {
      double maxIdf = 0.0;
      LinkedHashSet<String> covered = new LinkedHashSet<>();
      for (String t : c.tokens) {
        Integer d = df.get(t);
        if (d == null || d <= 0 || d > rarityThreshold) continue;
        double tokenIdf = Math.log((numDocs - d + 0.5) / (d + 0.5) + 1.0);
        if (tokenIdf > maxIdf) maxIdf = tokenIdf;
        covered.add(t);
      }
      c.entityScore = maxIdf;
      c.entityCoverage = new ArrayList<>(covered);
    }
  }

  /**
   * NER_MEMBERSHIP: a window's entity score is the count of distinct document-NER-entity tokens
   * present in the window. The bridge name, being a named entity of the doc, marks its window.
   */
  private void scoreNerMembership(List<Candidate> candidates, String docEntityText) {
    Set<String> entityTokens = tokenize(docEntityText == null ? "" : docEntityText);
    if (entityTokens.isEmpty()) return;
    for (Candidate c : candidates) {
      LinkedHashSet<String> covered = new LinkedHashSet<>();
      for (String t : tokenize(c.text)) {
        if (entityTokens.contains(t)) covered.add(t);
      }
      c.entityScore = covered.size();
      c.entityCoverage = new ArrayList<>(covered);
    }
  }

  // ---- helpers ----

  private static Set<String> tokenize(String s) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    var m = TOKEN.matcher(s);
    while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
    return out;
  }

  private static List<String> legsIn(Candidate c, List<HighlightingOps.TermMatch> matchOffsets) {
    LinkedHashSet<String> legs = new LinkedHashSet<>();
    for (HighlightingOps.TermMatch mo : matchOffsets) {
      if (mo.startOffset() >= c.geom.winStart() && mo.endOffset() <= c.geom.winEnd()) {
        legs.add(mo.term());
      }
    }
    return new ArrayList<>(legs);
  }

  private static boolean overlapsTaken(int winStart, int winEnd, List<int[]> taken) {
    for (int[] r : taken) {
      int overlapStart = Math.max(winStart, r[0]);
      int overlapEnd = Math.min(winEnd, r[1]);
      if (overlapEnd > overlapStart) {
        int overlapLen = overlapEnd - overlapStart;
        int minLen = Math.min(winEnd - winStart, r[1] - r[0]);
        if (minLen > 0 && overlapLen > minLen / 2) return true;
      }
    }
    return false;
  }

  private static int countNewlines(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') n++;
    }
    return n;
  }

  private static final class Candidate {
    final HighlightingOps.WindowGeom geom;
    final String text;
    final double base;
    Set<String> tokens = Set.of();
    double entityScore = 0.0;
    List<String> entityCoverage = List.of();

    Candidate(HighlightingOps.WindowGeom geom, String text, double base) {
      this.geom = geom;
      this.text = text;
      this.base = base;
    }
  }
}
